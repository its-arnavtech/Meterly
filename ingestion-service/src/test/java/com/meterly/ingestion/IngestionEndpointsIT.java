package com.meterly.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.crypto.Hashing;
import com.meterly.common.event.Topics;
import com.meterly.common.event.UsageEventMessage;
import com.meterly.common.security.Role;
import com.meterly.ingestion.support.FakeTenantService;
import com.meterly.testsupport.MeterlyRedis;
import com.meterly.testsupport.MeterlyRedpanda;
import com.meterly.testsupport.TestJwt;
import com.meterly.testsupport.TestJwtConfiguration;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end coverage of {@code POST /v1/usage-events} and {@code /batch} against the full contract
 * (docs/api-contract.md §2), driven through the real HTTP filter chain and a real Redis/Kafka
 * (Testcontainers) but a JDK {@code HttpServer}-based fake in place of tenant-service.
 *
 * <p>The per-tenant rate limit is overridden to a small bucket (capacity 5, refill 5/s) for the whole
 * class; every test uses its own freshly-generated tenant id, so this never causes cross-test
 * interference and keeps the dedicated rate-limit test fast and deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtConfiguration.class)
class IngestionEndpointsIT {

    private static FakeTenantService tenantService;
    private static KafkaConsumer<String, String> usageEvents;
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private TestRestTemplate rest;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        MeterlyRedis.registerProperties(registry);
        MeterlyRedpanda.registerProperties(registry);
        tenantService = new FakeTenantService();
        tenantService.start();
        registry.add("meterly.ingestion.tenant-service-url", () -> "http://localhost:" + tenantService.port());
        // A slow refill relative to a local HTTP+Redis round trip: 5 rapid calls exhaust the bucket
        // faster than it can refill even a single token back, so the 6th reliably sees 429.
        registry.add("meterly.ingestion.rate-limit.capacity", () -> "5");
        registry.add("meterly.ingestion.rate-limit.refill-per-second", () -> "1");
    }

    @BeforeAll
    static void startConsumer() {
        Properties consumerProps = new Properties();
        consumerProps.put("bootstrap.servers", MeterlyRedpanda.bootstrapServers());
        consumerProps.put("group.id", "ingestion-endpoints-it-" + UUID.randomUUID());
        consumerProps.put("key.deserializer", StringDeserializer.class.getName());
        consumerProps.put("value.deserializer", StringDeserializer.class.getName());
        consumerProps.put("auto.offset.reset", "earliest");
        usageEvents = new KafkaConsumer<>(consumerProps);
        usageEvents.subscribe(List.of(Topics.USAGE_EVENTS));
    }

    @AfterAll
    static void tearDown() {
        usageEvents.close();
        tenantService.stop();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private record Tenant(UUID tenantId, UUID resourceId, String apiKey, UUID apiKeyId) {
    }

    /** Registers a fresh tenant, one active resource and an API key with tenant-service (fake), and
     * mirrors the resource set for the JWT-lookup endpoint too so either credential type works. */
    private Tenant newTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID apiKeyId = UUID.randomUUID();
        String apiKey = "mk_live_" + Hashing.randomToken(32);
        tenantService.registerApiKey(apiKey, tenantId, apiKeyId, Set.of(resourceId));
        tenantService.setTenantResources(tenantId, Set.of(resourceId));
        return new Tenant(tenantId, resourceId, apiKey, apiKeyId);
    }

    private HttpHeaders apiKeyHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        return headers;
    }

    private HttpHeaders jwtHeaders(UUID tenantId, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, TestJwt.bearer(tenantId, UUID.randomUUID(), role));
        return headers;
    }

    private Map<String, Object> body(UUID resourceId, String idempotencyKey, long quantity, Instant occurredAt) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("resource_id", resourceId.toString());
        body.put("idempotency_key", idempotencyKey);
        body.put("quantity", quantity);
        if (occurredAt != null) {
            body.put("occurred_at", occurredAt.toString());
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> postSingle(HttpHeaders headers, Map<String, Object> body) {
        return rest.exchange("/v1/usage-events", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> postBatch(HttpHeaders headers, List<Map<String, Object>> events) {
        return rest.exchange("/v1/usage-events/batch", HttpMethod.POST,
                new HttpEntity<>(Map.of("events", events), headers), Map.class);
    }

    private ConsumerRecord<String, String> awaitRecord(UUID tenantId, String idempotencyKey, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = usageEvents.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, String> record : records) {
                if (matches(record, tenantId, idempotencyKey)) {
                    return record;
                }
            }
        }
        throw new AssertionError("No usage-events record for idempotency key " + idempotencyKey
                + " within " + timeout);
    }

    private List<ConsumerRecord<String, String>> pollFor(UUID tenantId, String idempotencyKey, Duration window) {
        List<ConsumerRecord<String, String>> matches = new ArrayList<>();
        Instant deadline = Instant.now().plus(window);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = usageEvents.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, String> record : records) {
                if (matches(record, tenantId, idempotencyKey)) {
                    matches.add(record);
                }
            }
        }
        return matches;
    }

    private boolean matches(ConsumerRecord<String, String> record, UUID tenantId, String idempotencyKey) {
        try {
            UsageEventMessage message = MAPPER.readValue(record.value(), UsageEventMessage.class);
            return message.tenantId().equals(tenantId) && message.idempotencyKey().equals(idempotencyKey);
        } catch (Exception e) {
            return false;
        }
    }

    // ---- accepted / Kafka shape --------------------------------------------------------------------

    @Test
    void acceptedEventIsPublishedWithTenantKeyAndExactFields() throws JsonProcessingException {
        Tenant tenant = newTenant();
        String idempotencyKey = "req-" + UUID.randomUUID();
        // Relative to "now" (not a fixed calendar date) so this stays inside the default 1h age window
        // no matter when the suite runs; truncated to millis since that is the JSON wire precision.
        Instant occurredAt = Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

        ResponseEntity<Map> response = postSingle(apiKeyHeaders(tenant.apiKey()),
                body(tenant.resourceId(), idempotencyKey, 3, occurredAt));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().get("status")).isEqualTo("accepted");
        assertThat(response.getBody().get("idempotency_key")).isEqualTo(idempotencyKey);
        assertThat(response.getBody().get("received_at")).isNotNull();
        assertThat(response.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("5");
        assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining")).isNotNull();

        ConsumerRecord<String, String> record = awaitRecord(tenant.tenantId(), idempotencyKey, Duration.ofSeconds(10));
        assertThat(record.key()).isEqualTo(tenant.tenantId().toString());
        UsageEventMessage message = MAPPER.readValue(record.value(), UsageEventMessage.class);
        assertThat(message.tenantId()).isEqualTo(tenant.tenantId());
        assertThat(message.resourceId()).isEqualTo(tenant.resourceId());
        assertThat(message.quantity()).isEqualTo(3L);
        assertThat(message.occurredAt()).isEqualTo(occurredAt);
        Header requestIdHeader = record.headers().lastHeader("x-request-id");
        assertThat(requestIdHeader).isNotNull();
    }

    // ---- idempotency --------------------------------------------------------------------------------

    @Test
    void duplicateRequestReturns200AndDoesNotRepublish() {
        Tenant tenant = newTenant();
        String idempotencyKey = "req-" + UUID.randomUUID();
        Map<String, Object> requestBody = body(tenant.resourceId(), idempotencyKey, 2, null);

        ResponseEntity<Map> first = postSingle(apiKeyHeaders(tenant.apiKey()), requestBody);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitRecord(tenant.tenantId(), idempotencyKey, Duration.ofSeconds(10));

        ResponseEntity<Map> duplicate = postSingle(apiKeyHeaders(tenant.apiKey()), requestBody);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(duplicate.getBody().get("status")).isEqualTo("duplicate");
        assertThat(pollFor(tenant.tenantId(), idempotencyKey, Duration.ofSeconds(2))).isEmpty();
    }

    @Test
    void sameKeyDifferentPayloadReturns409() {
        Tenant tenant = newTenant();
        String idempotencyKey = "req-" + UUID.randomUUID();
        postSingle(apiKeyHeaders(tenant.apiKey()), body(tenant.resourceId(), idempotencyKey, 2, null));
        awaitRecord(tenant.tenantId(), idempotencyKey, Duration.ofSeconds(10));

        ResponseEntity<Map> reused = postSingle(apiKeyHeaders(tenant.apiKey()),
                body(tenant.resourceId(), idempotencyKey, 99, null));

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reused.getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    // ---- resource validation, incl. refresh-once -----------------------------------------------------

    @Test
    void unknownResourceIsRejectedAfterOneRefreshAttempt() {
        Tenant tenant = newTenant();
        UUID neverRegistered = UUID.randomUUID();

        ResponseEntity<Map> response = postSingle(apiKeyHeaders(tenant.apiKey()),
                body(neverRegistered, "req-" + UUID.randomUUID(), 1, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("UNKNOWN_RESOURCE");
        // One verify call to populate the auth cache, one more from the refresh-once path.
        assertThat(tenantService.verifyCallCount(tenant.apiKey())).isEqualTo(2);
    }

    @Test
    void resourceAddedAfterCachingIsFoundOnRefresh() {
        Tenant tenant = newTenant();
        // Warm the 60s API-key verification cache with only the original resource.
        postSingle(apiKeyHeaders(tenant.apiKey()), body(tenant.resourceId(), "req-" + UUID.randomUUID(), 1, null));
        UUID newResource = UUID.randomUUID();
        tenantService.addResourceToApiKey(tenant.apiKey(), newResource);
        String idempotencyKey = "req-" + UUID.randomUUID();

        ResponseEntity<Map> response = postSingle(apiKeyHeaders(tenant.apiKey()),
                body(newResource, idempotencyKey, 1, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitRecord(tenant.tenantId(), idempotencyKey, Duration.ofSeconds(10));
    }

    // ---- occurred_at window ---------------------------------------------------------------------------

    @Test
    void eventOlderThanTheAgeWindowIsRejected() {
        Tenant tenant = newTenant();

        ResponseEntity<Map> response = postSingle(apiKeyHeaders(tenant.apiKey()),
                body(tenant.resourceId(), "req-" + UUID.randomUUID(), 1, Instant.now().minus(Duration.ofHours(2))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("EVENT_TOO_OLD");
    }

    @Test
    void eventTooFarInTheFutureIsRejected() {
        Tenant tenant = newTenant();

        ResponseEntity<Map> response = postSingle(apiKeyHeaders(tenant.apiKey()),
                body(tenant.resourceId(), "req-" + UUID.randomUUID(), 1, Instant.now().plus(Duration.ofMinutes(30))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("EVENT_IN_FUTURE");
    }

    // ---- authentication ---------------------------------------------------------------------------------

    @Test
    void unknownApiKeyReturns401() {
        String bogusKey = "mk_live_" + Hashing.randomToken(32);

        ResponseEntity<Map> response = postSingle(apiKeyHeaders(bogusKey),
                body(UUID.randomUUID(), "req-" + UUID.randomUUID(), 1, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_API_KEY");
    }

    @Test
    void tenantServiceUnreachableReturns503() {
        Tenant tenant = newTenant();
        tenantService.setDown(true);
        try {
            ResponseEntity<Map> response = postSingle(apiKeyHeaders(tenant.apiKey()),
                    body(tenant.resourceId(), "req-" + UUID.randomUUID(), 1, null));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody().get("code")).isEqualTo("AUTH_UNAVAILABLE");
        } finally {
            tenantService.setDown(false);
        }
    }

    @Test
    void tenantAdminJwtCanIngest() {
        Tenant tenant = newTenant();
        String idempotencyKey = "req-" + UUID.randomUUID();

        ResponseEntity<Map> response = postSingle(jwtHeaders(tenant.tenantId(), Role.TENANT_ADMIN),
                body(tenant.resourceId(), idempotencyKey, 1, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitRecord(tenant.tenantId(), idempotencyKey, Duration.ofSeconds(10));
    }

    @Test
    void tenantUserJwtIsForbidden() {
        Tenant tenant = newTenant();

        ResponseEntity<Map> response = postSingle(jwtHeaders(tenant.tenantId(), Role.TENANT_USER),
                body(tenant.resourceId(), "req-" + UUID.randomUUID(), 1, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void noCredentialsReturns401() {
        ResponseEntity<Map> response = postSingle(new HttpHeaders(),
                body(UUID.randomUUID(), "req-" + UUID.randomUUID(), 1, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- rate limiting -----------------------------------------------------------------------------------

    @Test
    void burstBeyondTheBucketIsRateLimitedWithoutAffectingAnotherTenant() {
        Tenant tenant = newTenant();
        ResponseEntity<Map> last = null;
        for (int i = 0; i < 6; i++) {
            last = postSingle(apiKeyHeaders(tenant.apiKey()),
                    body(tenant.resourceId(), "req-" + UUID.randomUUID(), 1, null));
        }

        assertThat(last.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(last.getBody().get("code")).isEqualTo("RATE_LIMITED");
        assertThat(last.getHeaders().getFirst("Retry-After")).isNotNull();

        Tenant otherTenant = newTenant();
        ResponseEntity<Map> otherResponse = postSingle(apiKeyHeaders(otherTenant.apiKey()),
                body(otherTenant.resourceId(), "req-" + UUID.randomUUID(), 1, null));
        assertThat(otherResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    // ---- batch --------------------------------------------------------------------------------------------

    @Test
    void batchReturnsMixedPerItemResults() {
        Tenant tenant = newTenant();
        String acceptedKey = "req-" + UUID.randomUUID();
        String duplicateKey = "req-" + UUID.randomUUID();
        String unknownResourceKey = "req-" + UUID.randomUUID();

        postSingle(apiKeyHeaders(tenant.apiKey()), body(tenant.resourceId(), duplicateKey, 1, null));
        awaitRecord(tenant.tenantId(), duplicateKey, Duration.ofSeconds(10));

        List<Map<String, Object>> events = List.of(
                body(tenant.resourceId(), acceptedKey, 4, null),
                body(tenant.resourceId(), duplicateKey, 1, null),
                body(UUID.randomUUID(), unknownResourceKey, 1, null));

        ResponseEntity<Map> response = postBatch(apiKeyHeaders(tenant.apiKey()), events);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("results");
        assertThat(results).hasSize(3);
        Map<String, String> statusByKey = new java.util.HashMap<>();
        for (Map<String, Object> item : results) {
            statusByKey.put((String) item.get("idempotency_key"), (String) item.get("status"));
        }
        assertThat(statusByKey.get(acceptedKey)).isEqualTo("accepted");
        assertThat(statusByKey.get(duplicateKey)).isEqualTo("duplicate");
        assertThat(statusByKey.get(unknownResourceKey)).isEqualTo("rejected");
        awaitRecord(tenant.tenantId(), acceptedKey, Duration.ofSeconds(10));
    }

    // ---- tenant comes from the credential, never the body ----------------------------------------------------

    @Test
    void requestBodyCannotOverrideTheAuthenticatedTenant() throws JsonProcessingException {
        Tenant realTenant = newTenant();
        UUID foreignTenantId = UUID.randomUUID();
        String idempotencyKey = "req-" + UUID.randomUUID();
        Map<String, Object> maliciousBody = body(realTenant.resourceId(), idempotencyKey, 1, null);
        maliciousBody.put("tenant_id", foreignTenantId.toString());

        ResponseEntity<Map> response = postSingle(apiKeyHeaders(realTenant.apiKey()), maliciousBody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ConsumerRecord<String, String> record = awaitRecord(realTenant.tenantId(), idempotencyKey, Duration.ofSeconds(10));
        assertThat(record.key()).isEqualTo(realTenant.tenantId().toString());
        UsageEventMessage message = MAPPER.readValue(record.value(), UsageEventMessage.class);
        assertThat(message.tenantId()).isEqualTo(realTenant.tenantId());
        assertThat(message.tenantId()).isNotEqualTo(foreignTenantId);
    }
}
