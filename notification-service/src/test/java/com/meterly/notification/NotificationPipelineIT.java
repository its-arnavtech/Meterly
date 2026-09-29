package com.meterly.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.event.NotificationEvent;
import com.meterly.common.event.Topics;
import com.meterly.common.security.Role;
import com.meterly.notification.delivery.NotificationRetryJob;
import com.meterly.notification.email.EmailDeliveryException;
import com.meterly.notification.email.EmailSender;
import com.meterly.notification.email.OutgoingEmail;
import com.meterly.testsupport.MeterlyPostgres;
import com.meterly.testsupport.MeterlyRedpanda;
import com.meterly.testsupport.TestJwt;
import com.meterly.testsupport.TestJwtConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * End-to-end through the real Kafka listener: publish JSON to {@code notification-events}, observe
 * notification rows and sends. Each test uses fresh tenant ids so in-flight records from one test
 * cannot interfere with another.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meterly.notification.retry.enabled=false",
        "meterly.notification.consumer.max-retries=1",
        "meterly.notification.consumer.initial-backoff=100ms"
})
@Import({TestJwtConfiguration.class, NotificationPipelineIT.RecordingMail.class})
class NotificationPipelineIT {

    static final List<OutgoingEmail> SENT = new CopyOnWriteArrayList<>();
    static final AtomicBoolean FAIL_SENDS = new AtomicBoolean();

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper mapper;
    @Autowired NotificationRetryJob retryJob;
    @LocalServerPort int port;

    private final JdbcTemplate db = MeterlyPostgres.ownerJdbc();
    private UUID tenant;
    private UUID otherTenant;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MeterlyPostgres.registerDataSource(registry);
        MeterlyRedpanda.registerProperties(registry);
    }

    @TestConfiguration
    static class RecordingMail {
        @Bean
        @Primary
        EmailSender recordingSender() {
            return email -> {
                if (FAIL_SENDS.get()) {
                    throw new EmailDeliveryException("SMTP relay refused the message");
                }
                SENT.add(email);
            };
        }
    }

    @BeforeAll
    static void cleanDatabase() {
        MeterlyPostgres.truncateTenantData();
    }

    @BeforeEach
    void seedTenants() {
        SENT.clear();
        FAIL_SENDS.set(false);
        tenant = UUID.randomUUID();
        otherTenant = UUID.randomUUID();
        String tag = tenant.toString().substring(0, 8);
        db.update("INSERT INTO tenant (id, name) VALUES (?, 'Tenant One'), (?, 'Tenant Two')", tenant, otherTenant);
        insertUser(tenant, "admin1-" + tag + "@one.test", "TENANT_ADMIN", "ACTIVE");
        insertUser(tenant, "admin2-" + tag + "@one.test", "TENANT_ADMIN", "ACTIVE");
        insertUser(tenant, "viewer-" + tag + "@one.test", "TENANT_USER", "ACTIVE");
        insertUser(tenant, "gone-" + tag + "@one.test", "TENANT_ADMIN", "DISABLED");
        insertUser(otherTenant, "admin-" + tag + "@two.test", "TENANT_ADMIN", "ACTIVE");
    }

    @Test
    void invoiceEventNotifiesEachActiveAdminExactlyOnceEvenWhenRedelivered() throws Exception {
        NotificationEvent invoice = invoiceIssued(tenant);
        publish(tenant, invoice);
        awaitRows(invoice.eventId(), 2);

        // Redeliver the identical record, then publish a marker event; once the marker is processed the
        // redelivered record (earlier on the same partition, same key) has been processed too.
        publish(tenant, invoice);
        NotificationEvent marker = invoiceIssued(tenant);
        publish(tenant, marker);
        awaitRows(marker.eventId(), 2);

        assertThat(recipientsOf(invoice.eventId()))
                .containsExactlyInAnyOrder("admin1-" + tag() + "@one.test", "admin2-" + tag() + "@one.test");
        assertThat(statusesOf(invoice.eventId())).containsOnly("SENT");
        assertThat(SENT).hasSize(4);
        assertThat(SENT).allSatisfy(email -> assertThat(email.to()).endsWith("@one.test"));
    }

    @Test
    void usageThresholdEventIsDelivered() throws Exception {
        var threshold = new NotificationEvent.UsageThresholdCrossed(UUID.randomUUID(), tenant, Instant.now(),
                UUID.randomUUID(), "API calls", LocalDate.of(2026, 9, 1), 80, 8_000, 10_000);
        publish(tenant, threshold);
        awaitRows(threshold.eventId(), 2);

        assertThat(db.queryForList("SELECT DISTINCT type FROM notification WHERE event_id = ?", String.class,
                threshold.eventId())).containsExactly(NotificationEvent.USAGE_THRESHOLD_CROSSED);
        assertThat(SENT).extracting(OutgoingEmail::subject).allSatisfy(s -> assertThat(s).contains("80%"));
    }

    @Test
    void recordsThatCanNeverSucceedGoToTheDeadLetterTopic() throws Exception {
        String marker = "malformed-" + UUID.randomUUID();
        kafka.send(Topics.NOTIFICATION_EVENTS, tenant.toString(), "{\"not\":\"an event\",\"m\":\"" + marker + "\"}").get();
        String wrongKey = mapper.writeValueAsString(invoiceIssued(tenant));
        kafka.send(Topics.NOTIFICATION_EVENTS, otherTenant.toString(), wrongKey).get();

        List<String> deadLetters = readDeadLetters(Duration.ofSeconds(30), marker, wrongKey);

        assertThat(deadLetters).anySatisfy(v -> assertThat(v).contains(marker));
        assertThat(deadLetters).contains(wrongKey);
        assertThat(db.queryForObject("SELECT count(*) FROM notification WHERE tenant_id IN (?, ?)", Long.class,
                tenant, otherTenant)).isZero();
    }

    @Test
    void failedSendIsRecordedAndRetriedLater() throws Exception {
        FAIL_SENDS.set(true);
        NotificationEvent invoice = invoiceIssued(tenant);
        publish(tenant, invoice);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(statusesOf(invoice.eventId())).hasSize(2).containsOnly("FAILED"));
        assertThat(db.queryForList("SELECT error FROM notification WHERE event_id = ?", String.class,
                invoice.eventId())).allSatisfy(error -> assertThat(error).isNotBlank());

        FAIL_SENDS.set(false);
        db.update("UPDATE notification SET next_attempt_at = now() - interval '1 second' WHERE event_id = ?",
                invoice.eventId());
        retryJob.runOnce();

        assertThat(statusesOf(invoice.eventId())).containsOnly("SENT");
        assertThat(db.queryForList("SELECT attempts FROM notification WHERE event_id = ?", Integer.class,
                invoice.eventId())).containsOnly(2);
        assertThat(SENT).hasSize(2);
    }

    @Test
    void historyIsTenantScopedAndAdminOnly() {
        UUID ownEvent = UUID.randomUUID();
        UUID foreignEvent = UUID.randomUUID();
        insertNotification(tenant, ownEvent, "owner@one.test");
        insertNotification(otherTenant, foreignEvent, "someone@two.test");
        RestClient http = RestClient.create("http://localhost:" + port);

        Map<String, List<Map<String, Object>>> body = http.get().uri("/v1/notifications")
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(tenant, UUID.randomUUID(), Role.TENANT_ADMIN))
                .retrieve().body(new ParameterizedTypeReference<>() { });
        HttpStatusCode viewer = http.get().uri("/v1/notifications")
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(tenant, UUID.randomUUID(), Role.TENANT_USER))
                .exchange((request, response) -> response.getStatusCode());
        HttpStatusCode anonymous = http.get().uri("/v1/notifications")
                .exchange((request, response) -> response.getStatusCode());

        List<Map<String, Object>> items = body.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("recipient", "owner@one.test")
                .containsKeys("id", "type", "subject", "status", "created_at");
        assertThat(viewer.value()).isEqualTo(403);
        assertThat(anonymous.value()).isEqualTo(401);
    }

    private String tag() {
        return tenant.toString().substring(0, 8);
    }

    private NotificationEvent invoiceIssued(UUID tenantId) {
        return new NotificationEvent.InvoiceIssued(UUID.randomUUID(), tenantId, Instant.now(), UUID.randomUUID(),
                "INV-202608-000042", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1),
                new BigDecimal("278.69"), "USD");
    }

    private void publish(UUID key, NotificationEvent event) throws Exception {
        kafka.send(Topics.NOTIFICATION_EVENTS, key.toString(), mapper.writeValueAsString(event)).get();
    }

    private void awaitRows(UUID eventId, int expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(statusesOf(eventId)).hasSize(expected).doesNotContain("PENDING"));
    }

    private List<String> recipientsOf(UUID eventId) {
        return db.queryForList("SELECT recipient FROM notification WHERE event_id = ?", String.class, eventId);
    }

    private List<String> statusesOf(UUID eventId) {
        return db.queryForList("SELECT status FROM notification WHERE event_id = ?", String.class, eventId);
    }

    private void insertUser(UUID tenantId, String email, String role, String status) {
        db.update("INSERT INTO app_user (tenant_id, email, role, password_hash, status) VALUES (?, ?, ?, 'x', ?)",
                tenantId, email, role, status);
    }

    private void insertNotification(UUID tenantId, UUID eventId, String recipient) {
        db.update("""
                INSERT INTO notification (tenant_id, event_id, type, recipient, subject, body, status, attempts, sent_at)
                VALUES (?, ?, 'invoice.issued', ?, 'Invoice ready', 'body', 'SENT', 1, now())
                """, tenantId, eventId, recipient);
    }

    private List<String> readDeadLetters(Duration timeout, String... expected) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, MeterlyRedpanda.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<String> values = new CopyOnWriteArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(Topics.NOTIFICATION_EVENTS_DLT));
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    values.add(record.value());
                }
                if (java.util.Arrays.stream(expected).allMatch(e -> values.stream().anyMatch(v -> v.contains(e)))) {
                    break;
                }
            }
        }
        return values;
    }
}
