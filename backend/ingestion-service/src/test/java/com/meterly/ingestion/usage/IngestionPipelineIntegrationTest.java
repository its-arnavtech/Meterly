package com.meterly.ingestion.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.ingestion.security.ApiKeyPrincipal;
import com.meterly.ingestion.security.TenantServiceClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = "meterly.internal.service-key=test-only-internal-service-key")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IngestionPipelineIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RESOURCE = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("10000000-0000-0000-0000-000000000099");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.7-alpine"))
            .withExposedPorts(6379);

    @Container
    static final RedpandaContainer REDPANDA = new RedpandaContainer(
            DockerImageName.parse("redpandadata/redpanda:v25.3.6")
                    .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda")
    );

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired StringRedisTemplate redisTemplate;
    @MockitoBean TenantServiceClient tenantServiceClient;

    @Test
    void authenticatedRequestConsumesRedisBucketAndPublishesToRealRedpanda() throws Exception {
        when(tenantServiceClient.introspect("pipeline-key"))
                .thenReturn(Optional.of(new ApiKeyPrincipal(TENANT, Set.of(RESOURCE))));

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "pipeline-proof-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()
        ))) {
            consumer.subscribe(List.of("usage-events"));
            mockMvc.perform(post("/v1/usage-events")
                            .header("X-Api-Key", "pipeline-key")
                            .contentType("application/json")
                            .content("""
                                    {"resource_id":"55555555-5555-5555-5555-555555555555",
                                     "idempotency_key":"10000000-0000-0000-0000-000000000099","quantity":9}
                                    """))
                    .andExpect(status().isAccepted());

            ConsumerRecord<String, String> matched = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (matched == null && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                    JsonNode payload = objectMapper.readTree(record.value());
                    if (payload.get("idempotencyKey").asText().equals(IDEMPOTENCY_KEY.toString())) {
                        matched = record;
                        break;
                    }
                }
            }

            assertThat(matched).isNotNull();
            JsonNode payload = objectMapper.readTree(matched.value());
            assertThat(matched.key()).isEqualTo(TENANT.toString());
            assertThat(payload.get("tenantId").asText()).isEqualTo(TENANT.toString());
            assertThat(payload.get("resourceId").asText()).isEqualTo(RESOURCE.toString());
            assertThat(payload.get("quantity").asLong()).isEqualTo(9);
            assertThat(redisTemplate.hasKey("rate-limit:usage:" + TENANT)).isTrue();
            System.out.printf(
                    "REDPANDA_PIPELINE_EVIDENCE status=202 tenant=%s redis_bucket=true topic=%s partition=%d quantity=9%n",
                    TENANT, matched.topic(), matched.partition()
            );
        }
    }
}
