package com.meterly.billing.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.platform.notification.InvoiceGeneratedMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class KeycloakRoleEnforcementIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.10-alpine")
            .withDatabaseName("meterly")
            .withUsername("meterly_owner")
            .withPassword("meterly-owner-local");

    @Container
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.7.0")
            .withExposedPorts(8080)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("keycloak-role-test-realm.json"),
                    "/opt/keycloak/data/import/meterly-test-realm.json"
            )
            .withCommand("start-dev", "--import-realm")
            .waitingFor(Wait.forHttp("/realms/meterly-test")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String keycloakBase = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "meterly_app");
        registry.add("spring.datasource.password", () -> "test-only-app-password-32-bytes-minimum");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.meterlyAppPassword",
                () -> "test-only-app-password-32-bytes-minimum");
        registry.add("meterly.auth.issuer-uri", () -> keycloakBase + "/realms/meterly-test");
        registry.add("meterly.auth.jwk-set-uri",
                () -> keycloakBase + "/realms/meterly-test/protocol/openid-connect/certs");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean KafkaTemplate<String, InvoiceGeneratedMessage> kafkaTemplate;

    @Test
    void keycloakTenantUserTokenCannotTriggerBilling() throws Exception {
        String token = obtainUserToken();
        mockMvc.perform(post("/v1/billing/run")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"period_start\":\"2026-07-01\",\"period_end\":\"2026-08-01\"}"))
                .andExpect(status().isForbidden());
    }

    private String obtainUserToken() throws Exception {
        String body = "grant_type=password"
                + "&client_id=meterly-role-test"
                + "&username=" + encode("viewer@meterly.test")
                + "&password=" + encode("viewer-test-password");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080)
                        + "/realms/meterly-test/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Keycloak token request returned " + response.statusCode());
        }
        JsonNode json = objectMapper.readTree(response.body());
        return json.path("access_token").asText();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
