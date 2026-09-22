package com.meterly.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.billing.outbox.OutboxRelay;
import com.meterly.billing.run.BillingService;
import com.meterly.billing.support.BillingFixtures;
import com.meterly.billing.support.BillingFixtures.Acme;
import com.meterly.billing.support.BillingIntegrationTest;
import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.event.NotificationEvent;
import com.meterly.common.event.Topics;
import com.meterly.testsupport.MeterlyRedpanda;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The transactional outbox relay against a real broker: billing publishes exactly the {@code invoice.issued}
 * rows it owns, in the wire format notification-service parses, and leaves other services' event types
 * alone.
 */
class OutboxRelayIT extends BillingIntegrationTest {

    private static final BillingPeriod AUGUST = BillingPeriod.startingOn(LocalDate.of(2026, 8, 1));

    @Autowired
    private BillingService billing;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void publishesInvoiceIssuedAndMarksTheRowPublished() {
        Acme acme = BillingFixtures.acme();
        UUID invoiceId = billing.run(acme.tenantId(), AUGUST).invoice().id();
        UUID eventId = BillingFixtures.owner().queryForObject(
                "SELECT id FROM outbox_event WHERE tenant_id = ?", UUID.class, acme.tenantId());

        OutboxRelay.RelayResult result = relay.relay();

        assertThat(result.published()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(BillingFixtures.count("SELECT count(*) FROM outbox_event WHERE published_at IS NOT NULL"))
                .isEqualTo(1);
        assertThat(BillingFixtures.owner().queryForObject(
                "SELECT last_error FROM outbox_event WHERE id = ?", String.class, eventId)).isNull();

        ConsumerRecord<String, String> record = awaitRecord(eventId).orElseThrow(
                () -> new AssertionError("no notification-events record for event " + eventId));
        assertThat(record.key()).isEqualTo(acme.tenantId().toString());
        assertThat(record.value())
                .contains("\"type\":\"invoice.issued\"")
                .contains("\"total_amount\":\"278.69\"")
                .contains("\"invoice_id\":\"" + invoiceId + "\"");
        assertThat(readEvent(record.value())).isInstanceOfSatisfying(NotificationEvent.InvoiceIssued.class, event -> {
            assertThat(event.eventId()).isEqualTo(eventId);
            assertThat(event.tenantId()).isEqualTo(acme.tenantId());
            assertThat(event.invoiceId()).isEqualTo(invoiceId);
            assertThat(event.invoiceNumber()).isEqualTo("INV-202608-000001");
            assertThat(event.periodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(event.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(event.totalAmount()).isEqualByComparingTo(new BigDecimal("278.69"));
            assertThat(event.currency()).isEqualTo("USD");
            assertThat(event.occurredAt()).isEqualTo(NOW);
        });
    }

    @Test
    void relaysOnlyItsOwnEventTypeAndStopsWhenThereIsNothingToDo() {
        UUID tenantId = BillingFixtures.tenant("Threshold owner");
        UUID thresholdEvent = insertOutboxEvent(tenantId, NotificationEvent.USAGE_THRESHOLD_CROSSED,
                "{\"type\":\"usage.threshold_crossed\",\"event_id\":\"" + UUID.randomUUID() + "\"}");

        OutboxRelay.RelayResult result = relay.relay();

        assertThat(result.published()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(BillingFixtures.owner().queryForObject(
                "SELECT published_at IS NULL FROM outbox_event WHERE id = ?", Boolean.class, thresholdEvent)).isTrue();
        assertThat(BillingFixtures.owner().queryForObject(
                "SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, thresholdEvent)).isZero();
    }

    @Test
    void anUnreadablePayloadIsRecordedAsAFailedAttemptInsteadOfBeingPublished() {
        UUID tenantId = BillingFixtures.tenant("Broken payload");
        UUID eventId = insertOutboxEvent(tenantId, NotificationEvent.INVOICE_ISSUED,
                "{\"type\":\"not.a.known.event\"}");

        OutboxRelay.RelayResult result = relay.relay();

        assertThat(result.published()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(BillingFixtures.owner().queryForObject(
                "SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, eventId)).isEqualTo(1);
        assertThat(BillingFixtures.owner().queryForObject(
                "SELECT last_error FROM outbox_event WHERE id = ?", String.class, eventId))
                .contains("InvalidTypeIdException");
        assertThat(BillingFixtures.owner().queryForObject(
                "SELECT published_at IS NULL FROM outbox_event WHERE id = ?", Boolean.class, eventId)).isTrue();
    }

    private UUID insertOutboxEvent(UUID tenantId, String eventType, String payload) {
        UUID id = UUID.randomUUID();
        BillingFixtures.owner().update("""
                INSERT INTO outbox_event (id, tenant_id, topic, event_key, event_type, payload)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, id, tenantId, Topics.NOTIFICATION_EVENTS, tenantId.toString(), eventType, payload);
        return id;
    }

    private NotificationEvent readEvent(String json) {
        try {
            return objectMapper.readValue(json, NotificationEvent.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("notification-service could not parse the record: " + e.getOriginalMessage(), e);
        }
    }

    /** Reads {@code notification-events} from the beginning and returns the record carrying {@code eventId}. */
    private Optional<ConsumerRecord<String, String>> awaitRecord(UUID eventId) {
        Properties config = new Properties();
        config.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, MeterlyRedpanda.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "billing-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        Instant deadline = Instant.now().plusSeconds(30);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(Topics.NOTIFICATION_EVENTS));
            List<ConsumerRecord<String, String>> seen = new ArrayList<>();
            while (Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofSeconds(1)).forEach(seen::add);
                Optional<ConsumerRecord<String, String>> match = seen.stream()
                        .filter(record -> record.value() != null && record.value().contains(eventId.toString()))
                        .findFirst();
                if (match.isPresent()) {
                    return match;
                }
            }
            return Optional.empty();
        }
    }
}
