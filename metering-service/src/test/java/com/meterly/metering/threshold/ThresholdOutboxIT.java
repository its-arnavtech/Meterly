package com.meterly.metering.threshold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.event.NotificationEvent;
import com.meterly.common.event.Topics;
import com.meterly.metering.MeteringIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/**
 * Usage thresholds (contract section 3): crossing 80% and 100% of the plan's included units writes
 * exactly one {@code usage_threshold} marker and one outbox row per crossing, inside the transaction of
 * the event that crossed it; the metering relay then publishes them to {@code notification-events}.
 */
class ThresholdOutboxIT extends MeteringIntegrationTest {

    @Test
    void eachCrossingIsEmittedExactlyOnceAndRelayedToNotificationEvents() {
        TenantFixture tenant = givenTenant("Threshold Inc");
        long eightyPercent = STARTER_INCLUDED_UNITS * 80 / 100;

        publish(usageEvent(tenant, "threshold-80-" + UUID.randomUUID(), eightyPercent));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(markers(tenant)).containsExactly(80));

        publish(usageEvent(tenant, "threshold-100-" + UUID.randomUUID(), STARTER_INCLUDED_UNITS - eightyPercent));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(markers(tenant)).containsExactly(80, 100));

        // Further usage (including duplicates) must not emit anything again.
        publish(usageEvent(tenant, "threshold-extra-" + UUID.randomUUID(), 5_000));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(projectedUnits(tenant.tenantId(), tenant.resourceId()))
                        .isEqualTo(STARTER_INCLUDED_UNITS + 5_000));
        assertThat(markers(tenant)).containsExactly(80, 100);
        assertThat(outboxRows(tenant)).isEqualTo(2);

        // The relay publishes every row exactly once and marks it published.
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(unpublishedOutboxRows(tenant)).isZero());
        List<ConsumerRecord<String, String>> published = drain(Topics.NOTIFICATION_EVENTS, Duration.ofSeconds(5))
                .stream()
                .filter(record -> record.value().contains(tenant.tenantId().toString()))
                .toList();

        assertThat(published).hasSize(2);
        assertThat(published).allSatisfy(record ->
                assertThat(record.key()).isEqualTo(tenant.tenantId().toString()));
        // The payload travels through a jsonb column, which reformats the JSON: assert on the tree,
        // not on the exact text, but still on the wire names and on money-free numeric types.
        JsonNode hundredPercent = published.stream()
                .map(record -> tree(record.value()))
                .filter(node -> node.path("threshold_percent").asInt() == 100)
                .findFirst()
                .orElseThrow();
        assertThat(hundredPercent.path("type").asText()).isEqualTo(NotificationEvent.USAGE_THRESHOLD_CROSSED);
        assertThat(hundredPercent.path("event_id").asText()).isNotBlank();
        assertThat(hundredPercent.path("tenant_id").asText()).isEqualTo(tenant.tenantId().toString());
        assertThat(hundredPercent.path("resource_id").asText()).isEqualTo(tenant.resourceId().toString());
        assertThat(hundredPercent.path("resource_name").asText()).isEqualTo("API calls");
        assertThat(hundredPercent.path("period_start").asText())
                .isEqualTo(BillingPeriod.current(clock).start().toString());
        assertThat(hundredPercent.path("occurred_at").asText()).endsWith("Z");
        assertThat(hundredPercent.path("threshold_percent").isNumber()).isTrue();
        assertThat(hundredPercent.path("total_units").isNumber()).isTrue();
        assertThat(hundredPercent.path("total_units").asLong()).isEqualTo(STARTER_INCLUDED_UNITS);
        assertThat(hundredPercent.path("included_units").asLong()).isEqualTo(STARTER_INCLUDED_UNITS);

        NotificationEvent event = read(hundredPercent.toString());
        String summary = switch (event) {
            case NotificationEvent.InvoiceIssued invoice -> "invoice " + invoice.invoiceNumber();
            case NotificationEvent.UsageThresholdCrossed crossed ->
                    crossed.thresholdPercent() + "% of " + crossed.includedUnits();
        };
        assertThat(summary).isEqualTo("100% of " + STARTER_INCLUDED_UNITS);
        assertThat(event.eventId()).isEqualTo(markerEventId(tenant, 100));
    }

    private NotificationEvent read(String json) {
        try {
            return objectMapper.readValue(json, NotificationEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode tree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Integer> markers(TenantFixture tenant) {
        return owner().queryForList(
                "SELECT threshold_percent FROM usage_threshold WHERE tenant_id = ? ORDER BY threshold_percent",
                Integer.class, tenant.tenantId());
    }

    private UUID markerEventId(TenantFixture tenant, int percent) {
        return owner().queryForObject(
                "SELECT event_id FROM usage_threshold WHERE tenant_id = ? AND threshold_percent = ?",
                UUID.class, tenant.tenantId(), percent);
    }

    private long outboxRows(TenantFixture tenant) {
        return owner().queryForObject("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND event_type = ?",
                Long.class, tenant.tenantId(), NotificationEvent.USAGE_THRESHOLD_CROSSED);
    }

    private long unpublishedOutboxRows(TenantFixture tenant) {
        return owner().queryForObject(
                "SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND published_at IS NULL",
                Long.class, tenant.tenantId());
    }
}
