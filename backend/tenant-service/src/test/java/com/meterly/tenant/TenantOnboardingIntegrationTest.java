package com.meterly.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "meterly.auth.jwt-secret=test-only-jwt-secret-at-least-32-bytes",
        "meterly.internal.service-key=test-only-internal-service-key"
})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TenantOnboardingIntegrationTest {
    private static final String GROWTH_PLAN = "22222222-2222-2222-2222-222222222223";
    private static final String INTERNAL_SERVICE_KEY = "test-only-internal-service-key";

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void onboardingCreatesIsolatedAdminResourcePlanAndApiKey() throws Exception {
        MvcResult onboarding = mockMvc.perform(post("/v1/tenants")
                        .contentType("application/json")
                        .content("""
                                {"name":"Acme Growth","admin_email":"owner@acme.test",
                                 "admin_password":"correct-horse-battery","plan_id":"%s"}
                                """.formatted(GROWTH_PLAN)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").isNotEmpty())
                .andExpect(jsonPath("$.resourceId").isNotEmpty())
                .andExpect(jsonPath("$.planId").value(GROWTH_PLAN))
                .andExpect(jsonPath("$.apiKey").value(org.hamcrest.Matchers.startsWith("mtr_live_")))
                .andReturn();
        JsonNode onboarded = objectMapper.readTree(onboarding.getResponse().getContentAsString());
        String tenantId = onboarded.get("tenantId").asText();
        String resourceId = onboarded.get("resourceId").asText();
        String apiKey = onboarded.get("apiKey").asText();

        String adminToken = login("owner@acme.test", "correct-horse-battery");
        mockMvc.perform(get("/v1/resources").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(resourceId))
                .andExpect(jsonPath("$[0].name").value("API calls"));
        mockMvc.perform(get("/v1/plans/current").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Growth"))
                .andExpect(jsonPath("$.includedUnits").value(5000))
                .andExpect(jsonPath("$.tiers.length()").value(2));

        mockMvc.perform(post("/internal/v1/api-keys/introspect")
                        .header("X-Internal-Service-Key", INTERNAL_SERVICE_KEY)
                        .contentType("application/json")
                        .content("{\"apiKey\":\"" + apiKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantId))
                .andExpect(jsonPath("$.resourceIds[0]").value(resourceId));

        mockMvc.perform(post("/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("""
                                {"email":"viewer@acme.test","password":"viewer-password","role":"TENANT_USER"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("TENANT_USER"));
        String userToken = login("viewer@acme.test", "viewer-password");
        mockMvc.perform(get("/v1/resources").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/plans/current").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/v1/tenants")
                        .contentType("application/json")
                        .content("""
                                {"name":"Duplicate Email","admin_email":"owner@acme.test",
                                 "admin_password":"another-password","plan_id":"%s"}
                                """.formatted(GROWTH_PLAN)))
                .andExpect(status().isConflict());
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
