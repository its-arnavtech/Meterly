package com.meterly.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.platform.tenant.TenantTransactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.hikari.maximum-pool-size=1",
                "meterly.auth.jwt-secret=test-only-jwt-secret-at-least-32-bytes",
                "meterly.internal.service-key=test-only-internal-service-key"
        }
)
@Import(TenantRlsHardeningIntegrationTest.RlsProbeConfiguration.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TenantRlsHardeningIntegrationTest {
    private static final String DEVELOPER_PLAN = "22222222-2222-2222-2222-222222222222";
    private static final String JWT_SECRET = "test-only-jwt-secret-at-least-32-bytes";
    private static final String INTERNAL_SERVICE_KEY = "test-only-internal-service-key";
    private static final AtomicInteger PROBE_QUERIES = new AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.10-alpine")
            .withDatabaseName("meterly")
            .withUsername("meterly_owner")
            .withPassword("meterly-owner-local")
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "meterly_app");
        registry.add("spring.datasource.password", () -> "test-only-app-password-32-bytes-minimum");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.meterlyAppPassword",
                () -> "test-only-app-password-32-bytes-minimum");
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void resetQueryCounter() {
        PROBE_QUERIES.set(0);
    }

    @Test
    void concurrentTenantRequestsReuseOnePhysicalConnectionWithoutLeakage() throws Exception {
        Onboarding tenantA = onboard("RLS Concurrent A", "rls-a@meterly.test");
        Onboarding tenantB = onboard("RLS Concurrent B", "rls-b@meterly.test");
        String tokenA = login(tenantA.email(), tenantA.password());
        String tokenB = login(tenantB.email(), tenantB.password());

        int requestCount = 60;
        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ProbeResponse>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < requestCount; index++) {
                boolean useA = index % 2 == 0;
                String token = useA ? tokenA : tokenB;
                UUID expectedTenant = useA ? tenantA.tenantId() : tenantB.tenantId();
                futures.add(executor.submit(() -> {
                    start.await();
                    HttpResponse<String> response = get("/test/rls-probe", token);
                    assertThat(response.statusCode()).isEqualTo(200);
                    JsonNode json = objectMapper.readTree(response.body());
                    return new ProbeResponse(
                            expectedTenant,
                            UUID.fromString(json.get("tenantId").asText()),
                            UUID.fromString(json.get("visibleTenantId").asText()),
                            json.get("backendPid").asInt()
                    );
                }));
            }
            start.countDown();
            List<ProbeResponse> results = new ArrayList<>();
            for (Future<ProbeResponse> future : futures) {
                results.add(future.get());
            }
            assertThat(results).allSatisfy(result -> {
                assertThat(result.contextTenant()).isEqualTo(result.expectedTenant());
                assertThat(result.visibleTenant()).isEqualTo(result.expectedTenant());
            });
            Set<Integer> backendPids = results.stream().map(ProbeResponse::backendPid).collect(java.util.stream.Collectors.toSet());
            assertThat(backendPids).hasSize(1);
            assertThat(PROBE_QUERIES).hasValue(requestCount);
            System.out.printf(
                    "RLS_CONCURRENCY_EVIDENCE requests=%d tenant_a=%d tenant_b=%d backend_pids=%s leaks=0%n",
                    requestCount, requestCount / 2, requestCount / 2, backendPids
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void signedJwtWithoutValidTenantClaimIsRejectedBeforeProbeQuery() throws Exception {
        Onboarding tenant = onboard("JWT Claim Probe", "jwt-claim@meterly.test");
        String validToken = login(tenant.email(), tenant.password());
        assertThat(get("/test/rls-probe", validToken).statusCode()).isEqualTo(200);
        assertThat(PROBE_QUERIES).hasValue(1);

        PROBE_QUERIES.set(0);
        String missingClaim = signedToken(Map.of(
                "sub", tenant.email(), "role", "TENANT_ADMIN",
                "exp", Instant.now().plusSeconds(300).getEpochSecond()
        ));
        assertThat(get("/test/rls-probe", missingClaim).statusCode()).isEqualTo(401);
        assertThat(PROBE_QUERIES).hasValue(0);

        String malformedClaim = signedToken(Map.of(
                "sub", tenant.email(), "tenant_id", "not-a-uuid", "role", "TENANT_ADMIN",
                "exp", Instant.now().plusSeconds(300).getEpochSecond()
        ));
        assertThat(get("/test/rls-probe", malformedClaim).statusCode()).isEqualTo(401);
        assertThat(PROBE_QUERIES).hasValue(0);
        System.out.println("JWT_TENANT_CLAIM_EVIDENCE missing=401 malformed=401 probe_queries=0");
    }

    @Test
    void internalApiKeyIntrospectionCarriesTenantContextWithoutTrustedDataBypass() throws Exception {
        Onboarding tenantA = onboard("Internal Call A", "internal-a@meterly.test");
        Onboarding tenantB = onboard("Internal Call B", "internal-b@meterly.test");

        JsonNode resultA = introspect(tenantA.apiKey(), INTERNAL_SERVICE_KEY, 200);
        JsonNode resultB = introspect(tenantB.apiKey(), INTERNAL_SERVICE_KEY, 200);
        assertThat(resultA.get("tenantId").asText()).isEqualTo(tenantA.tenantId().toString());
        assertThat(resultB.get("tenantId").asText()).isEqualTo(tenantB.tenantId().toString());
        assertThat(resultA.get("resourceIds").get(0).asText()).isEqualTo(tenantA.resourceId().toString());
        assertThat(resultB.get("resourceIds").get(0).asText()).isEqualTo(tenantB.resourceId().toString());
        assertThat(resultA.toString()).doesNotContain(tenantB.resourceId().toString());
        assertThat(resultB.toString()).doesNotContain(tenantA.resourceId().toString());
        introspect(tenantA.apiKey(), "wrong-internal-key", 401);
        System.out.printf(
                "INTERNAL_TENANT_CONTEXT_EVIDENCE tenant_a_resource=%s tenant_b_resource=%s cross_tenant_resources=0 wrong_secret=401%n",
                tenantA.resourceId(), tenantB.resourceId()
        );
    }

    private Onboarding onboard(String name, String email) throws Exception {
        String password = "correct-horse-battery";
        HttpResponse<String> response = post("/v1/tenants", null, null, """
                {"name":"%s","admin_email":"%s","admin_password":"%s","plan_id":"%s"}
                """.formatted(name, email, password, DEVELOPER_PLAN));
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        return new Onboarding(
                UUID.fromString(body.get("tenantId").asText()),
                UUID.fromString(body.get("resourceId").asText()),
                body.get("apiKey").asText(), email, password
        );
    }

    private String login(String email, String password) throws Exception {
        HttpResponse<String> response = post("/v1/auth/login", null, null,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).get("accessToken").asText();
    }

    private JsonNode introspect(String apiKey, String internalKey, int expectedStatus) throws Exception {
        HttpResponse<String> response = post(
                "/internal/v1/api-keys/introspect", null, internalKey,
                "{\"apiKey\":\"" + apiKey + "\"}"
        );
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        return response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String internalKey, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (internalKey != null) builder.header("X-Internal-Service-Key", internalKey);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private String signedToken(Map<String, Object> suppliedClaims) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> claims = new LinkedHashMap<>(suppliedClaims);
        String payload = encoder.encodeToString(objectMapper.writeValueAsBytes(claims));
        String unsigned = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return unsigned + "." + encoder.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.US_ASCII)));
    }

    record Onboarding(UUID tenantId, UUID resourceId, String apiKey, String email, String password) { }
    record ProbeResponse(UUID expectedTenant, UUID contextTenant, UUID visibleTenant, int backendPid) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class RlsProbeConfiguration {
        @Bean
        RlsProbeController rlsProbeController(JdbcTemplate jdbcTemplate) {
            return new RlsProbeController(jdbcTemplate);
        }
    }

    @RestController
    public static class RlsProbeController {
        private final JdbcTemplate jdbcTemplate;

        public RlsProbeController(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @GetMapping("/test/rls-probe")
        @TenantTransactional
        public Probe probe() {
            PROBE_QUERIES.incrementAndGet();
            return jdbcTemplate.queryForObject("""
                    SELECT current_setting('app.tenant_id')::uuid AS tenant_id,
                           id AS visible_tenant_id, pg_backend_pid() AS backend_pid
                    FROM tenant
                    """, (resultSet, row) -> new Probe(
                    resultSet.getObject("tenant_id", UUID.class),
                    resultSet.getObject("visible_tenant_id", UUID.class),
                    resultSet.getInt("backend_pid")
            ));
        }
    }

    record Probe(UUID tenantId, UUID visibleTenantId, int backendPid) { }
}
