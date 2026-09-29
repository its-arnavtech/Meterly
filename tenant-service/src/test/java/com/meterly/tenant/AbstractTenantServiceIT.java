package com.meterly.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.meterly.common.security.InternalTokenFilter;
import com.meterly.testsupport.MeterlyPostgres;
import com.meterly.testsupport.MeterlyRedis;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tenant-service integration tests: the real application on a random port, PostgreSQL with the
 * production migrations and roles (RLS enforced, connected as {@code meterly_app}) and Redis, both from
 * {@code meterly-test-support}. All test classes share the same properties so they share one context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meterly.tenant.bcrypt-strength=4",
        "meterly.tenant.refresh-token-purge-cron=-",
        "meterly.security.internal-token=" + AbstractTenantServiceIT.INTERNAL_TOKEN,
        "meterly.auth.refresh-cookie-secure=false",
        // The production 500 ms budget is too tight for the first connection to a fresh container.
        "spring.data.redis.timeout=5s",
        "spring.data.redis.connect-timeout=5s"})
abstract class AbstractTenantServiceIT {

    static final String INTERNAL_TOKEN = "integration-test-internal-token";
    static final String PASSWORD = "Meterly!2026";
    static final String REFRESH_COOKIE = "meterly_refresh";

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    private StringRedisTemplate redis;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MeterlyPostgres.registerDataSource(registry);
        MeterlyRedis.registerProperties(registry);
    }

    @BeforeEach
    void resetState() {
        MeterlyPostgres.truncateTenantData();
        try (RedisConnection connection = redis.getRequiredConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    /** An authenticated session: the access token plus the raw refresh cookie value. */
    protected record Session(String accessToken, String refreshToken, UUID userId, UUID tenantId, String email) {
    }

    // ------------------------------------------------------------------ requests

    protected ResponseEntity<JsonNode> get(String path, HttpHeaders headers) {
        return exchange(HttpMethod.GET, path, headers, null);
    }

    protected ResponseEntity<JsonNode> post(String path, Object body, HttpHeaders headers) {
        return exchange(HttpMethod.POST, path, headers, body);
    }

    protected ResponseEntity<JsonNode> patch(String path, Object body, HttpHeaders headers) {
        return exchange(HttpMethod.PATCH, path, headers, body);
    }

    protected ResponseEntity<JsonNode> put(String path, Object body, HttpHeaders headers) {
        return exchange(HttpMethod.PUT, path, headers, body);
    }

    protected ResponseEntity<JsonNode> delete(String path, HttpHeaders headers) {
        return exchange(HttpMethod.DELETE, path, headers, null);
    }

    protected ResponseEntity<JsonNode> exchange(HttpMethod method, String path, HttpHeaders headers, Object body) {
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    // ------------------------------------------------------------------ credentials

    protected HttpHeaders anonymous() {
        return new HttpHeaders();
    }

    protected HttpHeaders bearer(Session session) {
        return bearer(session.accessToken());
    }

    protected HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    protected HttpHeaders internal() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(InternalTokenFilter.HEADER, INTERNAL_TOKEN);
        return headers;
    }

    protected HttpHeaders refreshCookie(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, REFRESH_COOKIE + "=" + refreshToken);
        return headers;
    }

    // ------------------------------------------------------------------ flows

    protected ResponseEntity<JsonNode> signupRequest(String tenantName, String email, String password,
                                                     String planCode) {
        Map<String, String> body = planCode == null
                ? Map.of("tenant_name", tenantName, "admin_email", email, "admin_password", password)
                : Map.of("tenant_name", tenantName, "admin_email", email, "admin_password", password,
                        "plan_code", planCode);
        return post("/v1/tenants", body, anonymous());
    }

    /** Signs up a tenant and logs its admin in. */
    protected Session signup(String tenantName, String email) {
        ResponseEntity<JsonNode> response = signupRequest(tenantName, email, PASSWORD, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return login(email, PASSWORD);
    }

    protected Session login(String email, String password) {
        ResponseEntity<JsonNode> response = loginRequest(email, password);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return session(response);
    }

    protected ResponseEntity<JsonNode> loginRequest(String email, String password) {
        return post("/v1/auth/login", Map.of("email", email, "password", password), anonymous());
    }

    protected Session session(ResponseEntity<JsonNode> response) {
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        JsonNode user = body.get("user");
        return new Session(
                body.get("access_token").asText(),
                refreshTokenOf(response),
                UUID.fromString(user.get("id").asText()),
                UUID.fromString(user.get("tenant_id").asText()),
                user.get("email").asText());
    }

    /** Reads the value of the {@code meterly_refresh} cookie from a response, or null if it was cleared. */
    protected String refreshTokenOf(ResponseEntity<JsonNode> response) {
        String value = cookieHeader(response);
        if (value == null) {
            return null;
        }
        String token = value.substring(value.indexOf('=') + 1, value.indexOf(';'));
        return token.isEmpty() ? null : token;
    }

    protected String cookieHeader(ResponseEntity<JsonNode> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies == null) {
            return null;
        }
        return cookies.stream().filter(cookie -> cookie.startsWith(REFRESH_COOKIE + "=")).findFirst().orElse(null);
    }

    /** Creates a second user in the caller's tenant and returns their session. */
    protected Session createUser(Session admin, String email, String role) {
        ResponseEntity<JsonNode> created = post("/v1/users",
                Map.of("email", email, "password", PASSWORD, "role", role), bearer(admin));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return login(email, PASSWORD);
    }

    protected static String code(ResponseEntity<JsonNode> response) {
        JsonNode body = response.getBody();
        assertThat(body).as("problem+json body").isNotNull();
        assertThat(body.hasNonNull("code")).as("problem+json body has a code: %s", body).isTrue();
        return body.get("code").asText();
    }
}
