package com.meterly.billing.run;

import com.meterly.billing.domain.PlanPricing;
import com.meterly.billing.domain.ResourceUsage;
import com.meterly.billing.domain.Tier;
import com.meterly.common.billing.BillingPeriod;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL used by a billing run: the per-tenant/period lock, ledger totals, usage aggregates and the plan of
 * the subscription covering the period. Tenant-scoped statements must run in a tenant-bound transaction.
 */
@Repository
public class BillingRunRepository {

    private final JdbcClient jdbc;

    public BillingRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The plan a tenant is billed on for a period. */
    public record SubscribedPlan(UUID planId, PlanPricing pricing) {
    }

    /**
     * Serializes billing runs for the same tenant and period until the current transaction ends. The key is
     * a 64-bit hash of a billing-specific string; a collision merely serializes two unrelated runs.
     */
    public void lockTenantPeriod(UUID tenantId, BillingPeriod period) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "billing:" + tenantId + ":" + period.start())
                .query((rs, row) -> Boolean.TRUE)
                .single();
    }

    /**
     * Ledger totals for the period ({@code occurred_at} in {@code [start, end)}), per resource that is active
     * or had usage in the period, ordered by resource name. The ledger is the source of truth; Redis is never
     * consulted for billing.
     */
    public List<ResourceUsage> ledgerTotals(UUID tenantId, BillingPeriod period) {
        return jdbc.sql("""
                        SELECT r.id AS resource_id, r.name AS resource_name,
                               COALESCE(SUM(e.quantity), 0)::bigint AS total_units
                        FROM metered_resource r
                        LEFT JOIN usage_event e
                               ON e.tenant_id = r.tenant_id
                              AND e.resource_id = r.id
                              AND e.occurred_at >= :periodStart
                              AND e.occurred_at < :periodEnd
                        WHERE r.tenant_id = :tenantId
                        GROUP BY r.id, r.name, r.status
                        HAVING r.status = 'ACTIVE' OR COUNT(e.id) > 0
                        ORDER BY r.name, r.id
                        """)
                .param("tenantId", tenantId)
                .param("periodStart", utc(period.startInstant()))
                .param("periodEnd", utc(period.endInstant()))
                .query((rs, row) -> new ResourceUsage(rs.getObject("resource_id", UUID.class),
                        rs.getString("resource_name"), rs.getLong("total_units")))
                .list();
    }

    /** Materializes the period totals into {@code usage_period_aggregate} (insert or refresh). */
    public void upsertAggregates(UUID tenantId, BillingPeriod period, List<ResourceUsage> usage, Instant computedAt) {
        for (ResourceUsage resource : usage) {
            jdbc.sql("""
                            INSERT INTO usage_period_aggregate
                                   (tenant_id, resource_id, period_start, period_end, total_units, computed_at)
                            VALUES (:tenantId, :resourceId, :periodStart, :periodEnd, :totalUnits, :computedAt)
                            ON CONFLICT (tenant_id, resource_id, period_start) DO UPDATE
                               SET period_end = EXCLUDED.period_end,
                                   total_units = EXCLUDED.total_units,
                                   computed_at = EXCLUDED.computed_at
                            """)
                    .param("tenantId", tenantId)
                    .param("resourceId", resource.resourceId())
                    .param("periodStart", period.start())
                    .param("periodEnd", period.end())
                    .param("totalUnits", resource.totalUnits())
                    .param("computedAt", utc(computedAt))
                    .update();
        }
    }

    /**
     * The plan of the active subscription overlapping the period. Plan changes take effect at period
     * boundaries, so normally exactly one subscription overlaps; if several do, the latest start wins.
     */
    public Optional<SubscribedPlan> findSubscribedPlan(UUID tenantId, BillingPeriod period) {
        return jdbc.sql("""
                        SELECT p.id, p.code, p.name, p.base_price, p.included_units, p.currency
                        FROM subscription s
                        JOIN plan p ON p.id = s.plan_id
                        WHERE s.tenant_id = :tenantId
                          AND s.status = 'ACTIVE'
                          AND s.start_date < :periodEnd
                          AND (s.end_date IS NULL OR s.end_date > :periodStart)
                        ORDER BY s.start_date DESC, s.created_at DESC
                        LIMIT 1
                        """)
                .param("tenantId", tenantId)
                .param("periodStart", period.start())
                .param("periodEnd", period.end())
                .query((rs, row) -> new PlanRow(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getBigDecimal("base_price"), rs.getLong("included_units"),
                        rs.getString("currency")))
                .optional()
                .map(plan -> new SubscribedPlan(plan.id(), new PlanPricing(plan.code(), plan.name(),
                        plan.basePrice(), plan.includedUnits(), plan.currency(), tiersOf(plan.id()))));
    }

    /** Tenants the scheduler bills, via the audited SECURITY DEFINER function (ids only). */
    public List<UUID> activeTenantIds() {
        return jdbc.sql("SELECT tenant_id FROM system_active_tenant_ids() AS tenant_id")
                .query(UUID.class)
                .list();
    }

    private List<Tier> tiersOf(UUID planId) {
        return jdbc.sql("SELECT from_unit, to_unit, unit_price FROM pricing_tier WHERE plan_id = :planId ORDER BY from_unit")
                .param("planId", planId)
                .query((rs, row) -> new Tier(rs.getLong("from_unit"), rs.getObject("to_unit", Long.class),
                        rs.getBigDecimal("unit_price")))
                .list();
    }

    private record PlanRow(UUID id, String code, String name, BigDecimal basePrice, long includedUnits,
                           String currency) {
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
