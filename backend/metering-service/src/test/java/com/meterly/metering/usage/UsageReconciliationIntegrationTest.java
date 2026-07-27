package com.meterly.metering.usage;

import com.meterly.platform.tenant.TenantContext;
import com.meterly.platform.usage.UsageEventMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
        "meterly.auth.jwt-secret=test-only-jwt-secret-at-least-32-bytes"
})
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UsageReconciliationIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RESOURCE = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.10-alpine")
            .withDatabaseName("meterly")
            .withUsername("meterly_owner")
            .withPassword("meterly-owner-local")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "meterly_app");
        registry.add("spring.datasource.password", () -> "test-only-app-password-32-bytes-minimum");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.meterlyAppPassword",
                () -> "test-only-app-password-32-bytes-minimum");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired UsageMeteringService meteringService;
    @Autowired UsageReconciliationJob reconciliationJob;
    @Autowired StringRedisTemplate redisTemplate;
    @MockitoBean SimpMessagingTemplate messagingTemplate;
    @MockitoBean UsageEventConsumer usageEventConsumer;

    @Test
    void detectsLogsAndCorrectsRedisDriftFromPostgres(CapturedOutput output) {
        Instant occurredAt = Instant.now();
        UsageEventMessage event = new UsageEventMessage(
                TENANT, RESOURCE, UUID.randomUUID(), 1375, occurredAt);
        try (TenantContext.Scope ignored = TenantContext.open(TENANT)) {
            assertThat(meteringService.record(event)).isTrue();
        }
        String period = DateTimeFormatter.ofPattern("uuuu-MM")
                .withZone(ZoneOffset.UTC).format(occurredAt);
        String redisKey = UsageMeteringService.redisKey(TENANT, RESOURCE, period);
        redisTemplate.opsForValue().set(redisKey, "7");
        assertThat(redisTemplate.opsForValue().get(redisKey)).isEqualTo("7");

        UsageReconciliationJob.ReconciliationSummary summary = reconciliationJob.reconcileOpenPeriod();

        assertThat(summary.tenantCount()).isEqualTo(1);
        assertThat(summary.resourceCount()).isEqualTo(1);
        assertThat(summary.driftCount()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(redisKey)).isEqualTo("1375");
        assertThat(output).contains(
                "usage_reconciliation_drift",
                "tenant_id=" + TENANT,
                "resource_id=" + RESOURCE,
                "expected=1375",
                "actual=7"
        );
        System.out.printf(
                "RECONCILIATION_EVIDENCE tenant=%s resource=%s before=7 expected=1375 after=%s warnings=1%n",
                TENANT, RESOURCE, redisTemplate.opsForValue().get(redisKey)
        );
    }
}
