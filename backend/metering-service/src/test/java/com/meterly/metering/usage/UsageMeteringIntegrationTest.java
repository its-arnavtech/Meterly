package com.meterly.metering.usage;

import com.meterly.platform.tenant.TenantContext;
import com.meterly.platform.usage.UsageEventMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@Testcontainers
class UsageMeteringIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RESOURCE = UUID.fromString("55555555-5555-5555-5555-555555555555");
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.10-alpine")
            .withDatabaseName("meterly").withUsername("meterly_owner").withPassword("meterly-owner-local");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "meterly_app");
        registry.add("spring.datasource.password", () -> "meterly-app-local");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @MockitoBean StringRedisTemplate redisTemplate;
    @MockitoBean SimpMessagingTemplate messagingTemplate;
    @MockitoBean UsageEventConsumer usageEventConsumer;
    @Autowired UsageMeteringService service;

    @SuppressWarnings("unchecked")
    @Test
    void duplicateDeliveryIncrementsExactlyOnce() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.increment(anyString(), eq(1375L))).thenReturn(1375L);
        UsageEventMessage event = new UsageEventMessage(TENANT, RESOURCE, UUID.randomUUID(), 1375, Instant.parse("2026-07-15T12:00:00Z"));
        try (TenantContext.Scope ignored = TenantContext.open(TENANT)) {
            assertThat(service.record(event)).isTrue();
            assertThat(service.record(event)).isFalse();
            assertThat(service.authoritativeUsage(RESOURCE)).isEqualTo(1375);
        }
        verify(values, times(1)).increment(anyString(), eq(1375L));
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/usage/" + TENANT), any(UsageUpdate.class));
    }
}
