package com.meterly.billing.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@Import(TenantRlsIntegrationTest.QueryConfiguration.class)
class TenantRlsIntegrationTest {
    private static final UUID SEEDED_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.10-alpine")
            .withDatabaseName("meterly")
            .withUsername("meterly_owner")
            .withPassword("meterly-owner-local");

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
    private UnfilteredUserQuery query;

    @Test
    void rlsHidesSeededTenantRowsWhenApplicationQueryOmitsTenantFilter() {
        assertThat(countAs(SEEDED_TENANT)).isEqualTo(1);
        assertThat(countAs(OTHER_TENANT)).isZero();
        assertThat(countAs(SEEDED_TENANT)).isEqualTo(1);
    }

    @Test
    void tenantScopedOperationFailsClosedWithoutTenantContext() {
        assertThatThrownBy(query::countEveryUserWithoutTenantFilter)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tenant context is required");
    }

    private int countAs(UUID tenantId) {
        try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
            return query.countEveryUserWithoutTenantFilter();
        }
    }

    @TestConfiguration
    static class QueryConfiguration {
        @Bean
        UnfilteredUserQuery unfilteredUserQuery(JdbcTemplate jdbcTemplate) {
            return new UnfilteredUserQuery(jdbcTemplate);
        }
    }

    static class UnfilteredUserQuery {
        private final JdbcTemplate jdbcTemplate;

        UnfilteredUserQuery(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @TenantTransactional
        public int countEveryUserWithoutTenantFilter() {
            return jdbcTemplate.queryForObject("select count(*) from app_user", Integer.class);
        }
    }
}
