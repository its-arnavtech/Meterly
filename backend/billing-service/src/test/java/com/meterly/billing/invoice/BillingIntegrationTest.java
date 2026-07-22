package com.meterly.billing.invoice;

import com.meterly.platform.auth.AuthPrincipal;
import com.meterly.platform.auth.JwtService;
import com.meterly.platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BillingIntegrationTest {
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

    @Autowired BillingService billingService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtService jwtService;
    @Autowired MockMvc mockMvc;

    @Test
    void invoiceIsCorrectIdempotentAndAdminOnly() throws Exception {
        try (TenantContext.Scope ignored = TenantContext.open(TENANT)) {
            insertUsage(1375);
            InvoiceView first = billingService.run(TENANT, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1));
            InvoiceView second = billingService.run(TENANT, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1));
            assertThat(first.id()).isEqualTo(second.id());
            assertThat(first.totalAmount()).isEqualByComparingTo("56.50");
            assertThat(first.lineItems()).hasSize(2);
        }
        String userJwt = jwtService.issue(new AuthPrincipal("viewer@meterly.test", TENANT, "TENANT_USER"));
        mockMvc.perform(post("/v1/billing/run").header("Authorization", "Bearer " + userJwt)
                        .contentType("application/json").content("{\"period_start\":\"2026-07-01\",\"period_end\":\"2026-08-01\"}"))
                .andExpect(status().isForbidden());
        String adminJwt = jwtService.issue(new AuthPrincipal("admin@meterly.local", TENANT, "TENANT_ADMIN"));
        mockMvc.perform(get("/v1/invoices/" + UUID.randomUUID()).header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isNotFound());
    }

    private void insertUsage(long quantity) {
        jdbcTemplate.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, TENANT.toString());
        jdbcTemplate.update("INSERT INTO usage_event (id, tenant_id, resource_id, idempotency_key, quantity, occurred_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), TENANT, RESOURCE, UUID.randomUUID(), quantity, Timestamp.from(Instant.parse("2026-07-15T12:00:00Z")));
    }
}
