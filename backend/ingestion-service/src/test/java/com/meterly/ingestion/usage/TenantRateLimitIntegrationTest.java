package com.meterly.ingestion.usage;

import com.meterly.ingestion.security.ApiKeyPrincipal;
import com.meterly.ingestion.security.TenantServiceClient;
import com.meterly.platform.usage.UsageEventMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "meterly.rate-limit.capacity=3",
        "meterly.rate-limit.refill-per-second=1",
        "meterly.internal.service-key=test-only-internal-service-key"
})
@AutoConfigureMockMvc
class TenantRateLimitIntegrationTest {
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("11111111-1111-1111-1111-111111111112");
    private static final UUID RESOURCE = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired MockMvc mockMvc;
    @Autowired StringRedisTemplate redisTemplate;
    @MockitoBean KafkaTemplate<String, UsageEventMessage> kafkaTemplate;
    @MockitoBean KafkaAdmin kafkaAdmin;
    @MockitoBean TenantServiceClient tenantServiceClient;

    @BeforeEach
    void resetRedisAndMocks() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        when(tenantServiceClient.introspect("tenant-a-key"))
                .thenReturn(Optional.of(new ApiKeyPrincipal(TENANT_A, Set.of(RESOURCE))));
        when(tenantServiceClient.introspect("tenant-b-key"))
                .thenReturn(Optional.of(new ApiKeyPrincipal(TENANT_B, Set.of(RESOURCE))));
        when(kafkaTemplate.send(eq("usage-events"), any(String.class), any(UsageEventMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void tenantBurstIsRejectedWithoutThrottlingAnotherTenant() throws Exception {
        int[] tenantAStatuses = new int[5];
        for (int index = 0; index < 3; index++) {
            tenantAStatuses[index] = submit("tenant-a-key", index)
                    .andExpect(status().isAccepted()).andReturn().getResponse().getStatus();
        }
        MvcResult firstRejected = submit("tenant-a-key", 3)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1")).andReturn();
        tenantAStatuses[3] = firstRejected.getResponse().getStatus();
        MvcResult secondRejected = submit("tenant-a-key", 4)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After")).andReturn();
        tenantAStatuses[4] = secondRejected.getResponse().getStatus();

        int tenantBStatus = submit("tenant-b-key", 5)
                .andExpect(status().isAccepted()).andReturn().getResponse().getStatus();
        System.out.printf(
                "RATE_LIMIT_EVIDENCE tenant_a=%s retry_after=[%s,%s] tenant_b=[%d]%n",
                java.util.Arrays.toString(tenantAStatuses),
                firstRejected.getResponse().getHeader("Retry-After"),
                secondRejected.getResponse().getHeader("Retry-After"),
                tenantBStatus
        );
    }

    private ResultActions submit(String apiKey, int suffix) throws Exception {
        return mockMvc.perform(post("/v1/usage-events")
                .header("X-Api-Key", apiKey)
                .contentType("application/json")
                .content("""
                        {"resource_id":"55555555-5555-5555-5555-555555555555",
                         "idempotency_key":"10000000-0000-0000-0000-%012d","quantity":1}
                        """.formatted(suffix)));
    }
}
