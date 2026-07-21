package com.meterly.billing.metering;

import com.meterly.billing.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

@SpringBootTest
@Testcontainers(parallel = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdempotentMeteringIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RESOURCE = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-15T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.10-alpine")
            .withDatabaseName("meterly")
            .withUsername("meterly_owner")
            .withPassword("meterly-owner-local")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.7-alpine"))
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    static final RedpandaContainer REDPANDA = new RedpandaContainer(
            DockerImageName.parse("redpandadata/redpanda:v25.3.6"))
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void serviceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "meterly_app");
        registry.add("spring.datasource.password", () -> "meterly-app-local");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<String, UsageEventMessage> kafkaTemplate;

    @Autowired
    private UsageMeteringService meteringService;

    @Test
    @Timeout(90)
    void oneThousandEventsWithDuplicatesAndRedeliveryCountUniqueKeysExactly() {
        List<UUID> uniqueKeys = java.util.stream.IntStream.range(0, 950)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();
        List<UsageEventMessage> messages = new ArrayList<>();

        uniqueKeys.forEach(key -> messages.add(event(key)));
        uniqueKeys.subList(0, 50).forEach(key -> messages.add(event(key))); // 5% client duplicates
        uniqueKeys.subList(50, 100).forEach(key -> messages.add(event(key))); // 5% redelivery

        CompletableFuture<?>[] sends = messages.stream()
                .map(message -> kafkaTemplate.send("usage-events", TENANT.toString(), message))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(sends).join();

        await().atMost(ofSeconds(45)).untilAsserted(() -> {
            assertThat(authoritativeUsage()).isEqualTo(950);
            assertThat(meteringService.currentUsage(TENANT, RESOURCE, "2026-07")).isEqualTo(950);
        });
    }

    private UsageEventMessage event(UUID idempotencyKey) {
        return new UsageEventMessage(TENANT, RESOURCE, idempotencyKey, 1, OCCURRED_AT);
    }

    private long authoritativeUsage() {
        try (TenantContext.Scope ignored = TenantContext.open(TENANT)) {
            return meteringService.authoritativeUsage(RESOURCE);
        }
    }
}
