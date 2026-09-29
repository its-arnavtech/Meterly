package com.meterly.metering;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.event.Topics;
import com.meterly.common.event.UsageEventMessage;
import com.meterly.common.redis.RedisKeys;
import com.meterly.testsupport.MeterlyPostgres;
import com.meterly.testsupport.MeterlyRedis;
import com.meterly.testsupport.MeterlyRedpanda;
import com.meterly.testsupport.TestJwtConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared setup for every metering integration test: one Spring context for the whole test JVM (all
 * subclasses use exactly these annotations and properties, so the context is cached and only one Kafka
 * listener ever runs in the consumer group), backed by the singleton PostgreSQL, Redis and Redpanda
 * containers from {@code meterly-test-support}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meterly.security.internal-token=test-internal-token-0123456789",
        "meterly.cors.allowed-origins=http://localhost:4200",
        "meterly.metering.outbox.relay-interval=500ms",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@Import(TestJwtConfiguration.class)
public abstract class MeteringIntegrationTest {

    protected static final String INTERNAL_TOKEN = "test-internal-token-0123456789";
    protected static final UUID STARTER_PLAN_ID = UUID.fromString("0a000000-0000-4000-8000-000000000001");
    protected static final long STARTER_INCLUDED_UNITS = 10_000;

    /** A tenant with one active metered resource and a STARTER subscription covering the open period. */
    protected record TenantFixture(UUID tenantId, UUID resourceId) {
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected Clock clock;

    @Autowired
    protected MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        MeterlyPostgres.registerDataSource(registry);
        MeterlyRedis.registerProperties(registry);
        MeterlyRedpanda.registerProperties(registry);
    }

    @BeforeEach
    void resetTenantData() {
        MeterlyPostgres.truncateTenantData();
    }

    /** Current value of a counter, or 0 when it has not been registered yet. */
    protected double counterValue(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    protected static JdbcTemplate owner() {
        return MeterlyPostgres.ownerJdbc();
    }

    protected TenantFixture givenTenant(String name) {
        UUID tenantId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        JdbcTemplate owner = owner();
        owner.update("INSERT INTO tenant (id, name) VALUES (?, ?)", tenantId, name);
        owner.update("INSERT INTO metered_resource (id, tenant_id, name, unit_label) VALUES (?, ?, 'API calls', 'call')",
                resourceId, tenantId);
        owner.update("INSERT INTO subscription (tenant_id, plan_id, start_date) VALUES (?, ?, ?)",
                tenantId, STARTER_PLAN_ID, BillingPeriod.current(clock).start());
        return new TenantFixture(tenantId, resourceId);
    }

    protected UsageEventMessage usageEvent(TenantFixture tenant, String idempotencyKey, long quantity) {
        return usageEvent(tenant, idempotencyKey, quantity, clock.instant());
    }

    protected UsageEventMessage usageEvent(TenantFixture tenant, String idempotencyKey, long quantity,
                                           Instant occurredAt) {
        return new UsageEventMessage(idempotencyKey, tenant.tenantId(), tenant.resourceId(), quantity,
                occurredAt, clock.instant(), "test-" + idempotencyKey);
    }

    /** Publishes a usage event exactly as ingestion-service does (key = tenant id) and waits for the ack. */
    protected void publish(UsageEventMessage event) {
        try {
            kafkaTemplate.send(Topics.USAGE_EVENTS, event.tenantId().toString(), json(event))
                    .get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    protected String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected long ledgerCount(UUID tenantId) {
        return owner().queryForObject("SELECT count(*) FROM usage_event WHERE tenant_id = ?", Long.class, tenantId);
    }

    protected long ledgerUnits(UUID tenantId, UUID resourceId) {
        return owner().queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM usage_event WHERE tenant_id = ? AND resource_id = ?",
                Long.class, tenantId, resourceId);
    }

    protected Long projectedUnits(UUID tenantId, UUID resourceId) {
        List<Long> units = owner().query("""
                        SELECT total_units FROM usage_counter_state
                         WHERE tenant_id = ? AND resource_id = ? AND period_start = ?
                        """,
                (rs, row) -> rs.getLong(1), tenantId, resourceId, BillingPeriod.current(clock).start());
        return units.isEmpty() ? null : units.getFirst();
    }

    protected Long cachedUnits(UUID tenantId, UUID resourceId) {
        String value = redis.opsForValue()
                .get(RedisKeys.usageCounter(tenantId, resourceId, BillingPeriod.current(clock)));
        return value == null ? null : Long.valueOf(value);
    }

    protected LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** A consumer on a throwaway group, used to inspect what the service published. */
    protected static KafkaConsumer<String, String> testConsumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, MeterlyRedpanda.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
    }

    protected static AdminClient admin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                MeterlyRedpanda.bootstrapServers()));
    }

    /** Reads everything currently on a topic (up to a short poll budget). */
    protected static List<ConsumerRecord<String, String>> drain(String topic, Duration budget) {
        try (KafkaConsumer<String, String> consumer = testConsumer()) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.nanoTime() + budget.toNanos();
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }
}
