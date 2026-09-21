package com.meterly.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterly.common.tenant.TenantAwareTransactionManager;
import com.meterly.common.tenant.TenantContext;
import com.meterly.testsupport.MeterlyPostgres;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PDD section 12 "Integration - RLS": a query with no tenant filter in its SQL still returns only the
 * bound tenant's rows, including when two tenants' transactions run back-to-back on the <em>same</em>
 * pooled connection (the pool is capped at one connection to force reuse).
 */
class RowLevelSecurityIT {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");
    private static final UUID RESOURCE_A = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000a1");
    private static final UUID RESOURCE_B = UUID.fromString("bbbbbbbb-0000-4000-8000-0000000000b1");

    private static HikariDataSource pool;
    private static JdbcClient jdbc;
    private static TransactionTemplate tx;

    @BeforeAll
    static void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MeterlyPostgres.jdbcUrl());
        config.setUsername(MeterlyPostgres.APP_ROLE);
        config.setPassword(MeterlyPostgres.APP_PASSWORD);
        config.setMaximumPoolSize(1);
        pool = new HikariDataSource(config);
        jdbc = JdbcClient.create(pool);
        tx = new TransactionTemplate(new TenantAwareTransactionManager(pool));
    }

    @AfterAll
    static void tearDown() {
        pool.close();
    }

    @BeforeEach
    void seed() {
        MeterlyPostgres.truncateTenantData();
        JdbcTemplate owner = MeterlyPostgres.ownerJdbc();
        owner.update("INSERT INTO tenant (id, name) VALUES (?, 'Tenant A'), (?, 'Tenant B')", TENANT_A, TENANT_B);
        owner.update("INSERT INTO metered_resource (id, tenant_id, name, unit_label) VALUES (?, ?, 'API calls', 'call'), (?, ?, 'API calls', 'call')",
                RESOURCE_A, TENANT_A, RESOURCE_B, TENANT_B);
        for (int i = 0; i < 3; i++) {
            insertEventAsOwner(owner, TENANT_A, RESOURCE_A, "a-" + i, 10);
        }
        for (int i = 0; i < 5; i++) {
            insertEventAsOwner(owner, TENANT_B, RESOURCE_B, "b-" + i, 7);
        }
        owner.update("INSERT INTO app_user (tenant_id, email, role, password_hash) VALUES (?, 'admin@a.test', 'TENANT_ADMIN', 'x')", TENANT_A);
    }

    @Test
    void unfilteredQueryReturnsOnlyTheBoundTenantsRowsUnderConnectionReuse() {
        String pidA = TenantContext.callAs(TENANT_A, () -> tx.execute(s -> backendPid()));
        List<UUID> seenByA = TenantContext.callAs(TENANT_A, () -> tx.execute(s -> tenantIdsInLedger()));
        List<UUID> seenByB = TenantContext.callAs(TENANT_B, () -> tx.execute(s -> tenantIdsInLedger()));
        String pidB = TenantContext.callAs(TENANT_B, () -> tx.execute(s -> backendPid()));

        assertThat(pidA).as("both tenants must have used the same physical connection").isEqualTo(pidB);
        assertThat(seenByA).hasSize(3).containsOnly(TENANT_A);
        assertThat(seenByB).hasSize(5).containsOnly(TENANT_B);
    }

    @Test
    void noBoundTenantSeesNothing() {
        List<UUID> ledger = tx.execute(s -> tenantIdsInLedger());
        Long tenants = tx.execute(s -> jdbc.sql("SELECT count(*) FROM tenant").query(Long.class).single());

        assertThat(ledger).isEmpty();
        assertThat(tenants).isZero();
    }

    @Test
    void tenantSettingDoesNotSurviveCommitOnThePooledConnection() {
        TenantContext.runAs(TENANT_A, () -> tx.executeWithoutResult(s -> tenantIdsInLedger()));
        // Auto-commit statement outside any transaction, on the same single pooled connection.
        String leftover = jdbc.sql("SELECT coalesce(current_setting('app.tenant_id', true), '')").query(String.class).single();
        List<UUID> visible = jdbc.sql("SELECT tenant_id FROM usage_event").query(UUID.class).list();

        assertThat(leftover).isEmpty();
        assertThat(visible).isEmpty();
    }

    @Test
    void cannotWriteRowsForAnotherTenant() {
        assertThatThrownBy(() -> TenantContext.runAs(TENANT_A, () -> tx.executeWithoutResult(s ->
                insertEvent(TENANT_B, RESOURCE_B, "sneaky", 1))))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    @Test
    void cannotReferenceAnotherTenantsResource() {
        assertThatThrownBy(() -> TenantContext.runAs(TENANT_A, () -> tx.executeWithoutResult(s ->
                insertEvent(TENANT_A, RESOURCE_B, "cross-ref", 1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateIdempotencyKeyIsANoOp() {
        int first = TenantContext.callAs(TENANT_A, () -> tx.execute(s -> insertEvent(TENANT_A, RESOURCE_A, "dup-key", 5)));
        int second = TenantContext.callAs(TENANT_A, () -> tx.execute(s -> insertEvent(TENANT_A, RESOURCE_A, "dup-key", 5)));
        // The same key is independent per tenant.
        int otherTenant = TenantContext.callAs(TENANT_B, () -> tx.execute(s -> insertEvent(TENANT_B, RESOURCE_B, "dup-key", 5)));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(otherTenant).isEqualTo(1);
    }

    @Test
    void usageLedgerIsAppendOnlyForTheApplicationRole() {
        assertThatThrownBy(() -> TenantContext.runAs(TENANT_A, () -> tx.executeWithoutResult(s ->
                jdbc.sql("UPDATE usage_event SET quantity = 999").update())))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> TenantContext.runAs(TENANT_A, () -> tx.executeWithoutResult(s ->
                jdbc.sql("DELETE FROM usage_event").update())))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void loginLookupWorksBeforeATenantIsKnown() {
        Map<String, Object> row = tx.execute(s -> jdbc.sql("SELECT * FROM auth_find_user_by_email(?)")
                .param("ADMIN@A.TEST").query().singleRow());

        assertThat(row).containsEntry("tenant_id", TENANT_A).containsEntry("role", "TENANT_ADMIN");
    }

    @Test
    void invoicePerTenantPeriodIsUnique() {
        UUID plan = UUID.fromString("0a000000-0000-4000-8000-000000000001");
        String insert = "INSERT INTO invoice (tenant_id, invoice_number, plan_id, period_start, period_end, total_amount) "
                + "VALUES (?, ?, ?, DATE '2026-08-01', DATE '2026-09-01', 19.00) ON CONFLICT (tenant_id, period_start) DO NOTHING";
        int first = TenantContext.callAs(TENANT_A, () -> tx.execute(s ->
                jdbc.sql(insert).params(TENANT_A, "INV-T-1", plan).update()));
        int rerun = TenantContext.callAs(TENANT_A, () -> tx.execute(s ->
                jdbc.sql(insert).params(TENANT_A, "INV-T-2", plan).update()));

        assertThat(first).isEqualTo(1);
        assertThat(rerun).isZero();
    }

    @Test
    void seededPlanTiersAreContiguousAndStartAtTheIncludedUnits() {
        List<Map<String, Object>> plans = MeterlyPostgres.ownerJdbc().queryForList("SELECT id, included_units FROM plan");
        assertThat(plans).hasSize(3);
        for (Map<String, Object> plan : plans) {
            List<Map<String, Object>> tiers = MeterlyPostgres.ownerJdbc().queryForList(
                    "SELECT from_unit, to_unit FROM pricing_tier WHERE plan_id = ? ORDER BY from_unit", plan.get("id"));
            assertThat(tiers).isNotEmpty();
            assertThat(tiers.getFirst().get("from_unit")).isEqualTo(plan.get("included_units"));
            for (int i = 1; i < tiers.size(); i++) {
                assertThat(tiers.get(i).get("from_unit")).isEqualTo(tiers.get(i - 1).get("to_unit"));
            }
            assertThat(tiers.getLast().get("to_unit")).isNull();
        }
    }

    private static List<UUID> tenantIdsInLedger() {
        // Deliberately no WHERE tenant_id = ? : isolation must come from RLS alone.
        return jdbc.sql("SELECT tenant_id FROM usage_event").query(UUID.class).list();
    }

    private static String backendPid() {
        return jdbc.sql("SELECT pg_backend_pid()::text").query(String.class).single();
    }

    private static int insertEvent(UUID tenant, UUID resource, String key, long quantity) {
        return jdbc.sql("""
                        INSERT INTO usage_event (tenant_id, resource_id, idempotency_key, quantity, occurred_at, received_at)
                        VALUES (?, ?, ?, ?, now(), now())
                        ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                        """)
                .params(tenant, resource, key, quantity)
                .update();
    }

    private static void insertEventAsOwner(JdbcTemplate owner, UUID tenant, UUID resource, String key, long quantity) {
        owner.update("INSERT INTO usage_event (tenant_id, resource_id, idempotency_key, quantity, occurred_at, received_at) VALUES (?, ?, ?, ?, ?, ?)",
                tenant, resource, key, quantity, java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
    }
}
