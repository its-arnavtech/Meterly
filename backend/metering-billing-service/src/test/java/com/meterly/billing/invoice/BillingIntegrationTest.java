package com.meterly.billing.invoice;

import com.meterly.billing.tenant.TenantContext;
import com.meterly.billing.tenant.TenantTransactional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
        "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration")
@Testcontainers
@Import(BillingIntegrationTest.FixtureConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BillingIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RESOURCE = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 8, 1);

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
        registry.add("spring.datasource.password", () -> "meterly-app-local");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    private BillingService billingService;

    @Autowired
    private FixtureRepository fixture;

    @Test
    void generatedInvoiceMatchesHandCalculationAndRerunIsIdempotent() {
        asTenant(() -> fixture.insertUsage(1375));

        InvoiceView first = asTenant(() -> billingService.run(TENANT, PERIOD_START, PERIOD_END));
        InvoiceView second = asTenant(() -> billingService.run(TENANT, PERIOD_START, PERIOD_END));

        assertThat(first.id()).isEqualTo(second.id());
        assertThat(first.totalAmount()).isEqualByComparingTo(new BigDecimal("56.50"));
        assertThat(first.lineItems()).hasSize(2);
        assertThat(asTenant(fixture::invoiceCount)).isEqualTo(1);
        assertThat(asTenant(fixture::aggregateUnits)).isEqualTo(1375);
    }

    private void asTenant(Runnable operation) {
        try (TenantContext.Scope ignored = TenantContext.open(TENANT)) {
            operation.run();
        }
    }

    private <T> T asTenant(java.util.function.Supplier<T> operation) {
        try (TenantContext.Scope ignored = TenantContext.open(TENANT)) {
            return operation.get();
        }
    }

    @TestConfiguration
    static class FixtureConfiguration {
        @Bean
        FixtureRepository fixtureRepository(JdbcTemplate jdbcTemplate) {
            return new FixtureRepository(jdbcTemplate);
        }
    }

    static class FixtureRepository {
        private final JdbcTemplate jdbcTemplate;

        FixtureRepository(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @TenantTransactional
        public void insertUsage(long quantity) {
            jdbcTemplate.update("""
                    INSERT INTO usage_event
                        (id, tenant_id, resource_id, idempotency_key, quantity, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), TENANT, RESOURCE, UUID.randomUUID(), quantity,
                    Timestamp.from(Instant.parse("2026-07-15T12:00:00Z")));
        }

        @TenantTransactional
        public int invoiceCount() {
            return jdbcTemplate.queryForObject("SELECT count(*) FROM invoice", Integer.class);
        }

        @TenantTransactional
        public long aggregateUnits() {
            return jdbcTemplate.queryForObject(
                    "SELECT total_units FROM usage_period_aggregate WHERE period_start = ?",
                    Long.class,
                    java.sql.Date.valueOf(PERIOD_START));
        }
    }
}
