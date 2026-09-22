package com.meterly.metering.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.event.Topics;
import com.meterly.common.event.UsageEventMessage;
import com.meterly.metering.MeteringIntegrationTest;
import com.meterly.metering.MeteringMetrics;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/**
 * PDD section 12 "Integration - metering": a duplicate idempotency key and a simulated Kafka redelivery
 * both produce exactly one counted event, in the ledger, in the transactional projection and in Redis.
 * Also covers the records that must never be retried (another tenant's resource) and late events.
 */
class UsageIdempotencyIT extends MeteringIntegrationTest {

    @Test
    void clientRetryAndKafkaRedeliveryCountTheEventOnlyOnce() {
        TenantFixture tenant = givenTenant("Idempotency Inc");
        String key = "req-" + UUID.randomUUID();
        UsageEventMessage original = usageEvent(tenant, key, 7);
        // The client retried after a timeout: same idempotency key, a later receive time and request id.
        UsageEventMessage clientRetry = new UsageEventMessage(key, tenant.tenantId(), tenant.resourceId(), 7,
                original.occurredAt(), clock.instant(), "retry-" + key);

        double duplicatesBefore = duplicateCount();
        publish(original);
        publish(original);      // Kafka redelivery: byte-for-byte the same record
        publish(clientRetry);   // client retry: same key, different envelope

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(duplicateCount()).isEqualTo(duplicatesBefore + 2));

        assertThat(ledgerCount(tenant.tenantId())).isEqualTo(1);
        assertThat(ledgerUnits(tenant.tenantId(), tenant.resourceId())).isEqualTo(7);
        assertThat(projectedUnits(tenant.tenantId(), tenant.resourceId())).isEqualTo(7);
        assertThat(cachedUnits(tenant.tenantId(), tenant.resourceId())).isEqualTo(7);
    }

    @Test
    void eventForAnotherTenantsResourceIsDeadLetteredAndCountsNothing() {
        TenantFixture tenant = givenTenant("Victim Ltd");
        TenantFixture other = givenTenant("Neighbour Ltd");
        String key = "foreign-" + UUID.randomUUID();
        // The composite foreign key (tenant_id, resource_id) rejects another tenant's resource.
        UsageEventMessage foreign = new UsageEventMessage(key, tenant.tenantId(), other.resourceId(), 5,
                clock.instant(), clock.instant(), "test-" + key);

        double deadLettersBefore = counterValue(MeteringMetrics.DEAD_LETTERS, "reason", "integrity");
        publish(foreign);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(counterValue(MeteringMetrics.DEAD_LETTERS, "reason", "integrity"))
                        .isEqualTo(deadLettersBefore + 1));

        assertThat(ledgerCount(tenant.tenantId())).isZero();
        assertThat(ledgerCount(other.tenantId())).isZero();
        List<ConsumerRecord<String, String>> deadLetters = drain(Topics.USAGE_EVENTS_DLT, Duration.ofSeconds(5));
        assertThat(deadLetters).anyMatch(record -> record.value().contains(key));
    }

    @Test
    void malformedRecordIsDeadLetteredWithoutRetrying() {
        double deadLettersBefore = counterValue(MeteringMetrics.DEAD_LETTERS, "reason", "invalid");
        String marker = "not-json-" + UUID.randomUUID();
        kafkaTemplate.send(Topics.USAGE_EVENTS, UUID.randomUUID().toString(), "{\"broken\":\"" + marker + "\"}");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(counterValue(MeteringMetrics.DEAD_LETTERS, "reason", "invalid"))
                        .isEqualTo(deadLettersBefore + 1));

        assertThat(drain(Topics.USAGE_EVENTS_DLT, Duration.ofSeconds(5)))
                .anyMatch(record -> record.value().contains(marker));
    }

    @Test
    void eventForAnAlreadyInvoicedPeriodIsRecordedAndCounted() {
        TenantFixture tenant = givenTenant("Late Events Ltd");
        BillingPeriod closed = BillingPeriod.current(clock).previous();
        owner().update("""
                        INSERT INTO invoice (tenant_id, invoice_number, plan_id, period_start, period_end,
                                             currency, total_amount, plan_code, plan_name)
                        VALUES (?, ?, ?, ?, ?, 'USD', 19.00, 'STARTER', 'Starter')
                        """,
                tenant.tenantId(), "INV-TEST-" + UUID.randomUUID(), STARTER_PLAN_ID, closed.start(), closed.end());

        double lateBefore = counterValue(MeteringMetrics.LATE_EVENTS);
        String key = "late-" + UUID.randomUUID();
        publish(usageEvent(tenant, key, 3, closed.startInstant().plusSeconds(3600)));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(counterValue(MeteringMetrics.LATE_EVENTS)).isEqualTo(lateBefore + 1));

        assertThat(ledgerCount(tenant.tenantId())).isEqualTo(1);
        Long closedPeriodUnits = owner().queryForObject("""
                SELECT total_units FROM usage_counter_state
                 WHERE tenant_id = ? AND resource_id = ? AND period_start = ?
                """, Long.class, tenant.tenantId(), tenant.resourceId(), closed.start());
        assertThat(closedPeriodUnits).isEqualTo(3);
    }

    private double duplicateCount() {
        return counterValue(MeteringMetrics.EVENTS, "result", "duplicate");
    }
}
