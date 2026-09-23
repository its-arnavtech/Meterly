package com.meterly.billing.support;

import com.meterly.testsupport.MeterlyPostgres;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test data written through the owner connection (bypasses RLS, like the migrator/demo seed would). The
 * service under test only ever sees it through the RLS-restricted {@code meterly_app} role.
 */
public final class BillingFixtures {

    public static final UUID STARTER = UUID.fromString("0a000000-0000-4000-8000-000000000001");
    public static final UUID GROWTH = UUID.fromString("0a000000-0000-4000-8000-000000000002");

    private BillingFixtures() {
    }

    public static JdbcTemplate owner() {
        return MeterlyPostgres.ownerJdbc();
    }

    public static UUID tenant(String name) {
        UUID id = UUID.randomUUID();
        owner().update("INSERT INTO tenant (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    public static UUID resource(UUID tenantId, String name) {
        return resource(tenantId, name, "ACTIVE");
    }

    public static UUID resource(UUID tenantId, String name, String status) {
        UUID id = UUID.randomUUID();
        owner().update("INSERT INTO metered_resource (id, tenant_id, name, unit_label, status) VALUES (?, ?, ?, 'unit', ?)",
                id, tenantId, name, status);
        return id;
    }

    public static void subscribe(UUID tenantId, UUID planId, LocalDate startDate) {
        owner().update("INSERT INTO subscription (tenant_id, plan_id, start_date) VALUES (?, ?, ?)",
                tenantId, planId, startDate);
    }

    public static void subscribe(UUID tenantId, UUID planId, LocalDate startDate, LocalDate endDate) {
        owner().update("INSERT INTO subscription (tenant_id, plan_id, start_date, end_date) VALUES (?, ?, ?, ?)",
                tenantId, planId, startDate, endDate);
    }

    /** Appends one ledger row ({@code occurred_at} decides the billing period). */
    public static void usage(UUID tenantId, UUID resourceId, long quantity, String occurredAt) {
        Timestamp at = Timestamp.from(Instant.parse(occurredAt));
        owner().update("""
                INSERT INTO usage_event (tenant_id, resource_id, idempotency_key, quantity, occurred_at, received_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, tenantId, resourceId, "it-" + UUID.randomUUID(), quantity, at, at);
    }

    public static long count(String sql, Object... args) {
        Long value = owner().queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    /**
     * The demo tenant "Acme" (see R__demo_seed.sql) for period 2026-08, plus usage just outside the period
     * that must not be billed. Expected invoice: 19.00 + 180.00 + 75.00 + 4.69 = 278.69.
     */
    public static Acme acme() {
        UUID tenant = tenant("Acme Corp");
        UUID api = resource(tenant, "API calls");
        UUID hooks = resource(tenant, "Webhooks delivered");
        subscribe(tenant, STARTER, LocalDate.of(2026, 6, 1));
        usage(tenant, api, 100_000, "2026-08-01T00:00:00Z");          // first instant of the period: billed
        usage(tenant, api, 49_999, "2026-08-17T09:30:00Z");
        usage(tenant, api, 1, "2026-08-31T23:59:59.999999Z");         // last microsecond: billed
        usage(tenant, api, 5_000, "2026-07-31T23:59:59.999999Z");     // previous period: not billed
        usage(tenant, api, 7_777, "2026-09-01T00:00:00Z");            // next period: not billed
        usage(tenant, hooks, 10_000, "2026-08-03T12:00:00Z");
        usage(tenant, hooks, 2_345, "2026-08-29T18:00:00Z");
        return new Acme(tenant, api, hooks);
    }

    public record Acme(UUID tenantId, UUID apiCalls, UUID webhooks) {
    }

    /** The demo tenant "Globex": GROWTH, 250,000 API calls in 2026-08. Expected invoice: 99.00 + 150.00 = 249.00. */
    public static UUID globex() {
        UUID tenant = tenant("Globex");
        UUID api = resource(tenant, "API calls");
        subscribe(tenant, GROWTH, LocalDate.of(2026, 7, 1));
        usage(tenant, api, 250_000, "2026-08-10T00:00:00Z");
        return tenant;
    }
}
