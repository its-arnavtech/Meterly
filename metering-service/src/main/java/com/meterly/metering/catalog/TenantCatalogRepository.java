package com.meterly.metering.catalog;

import com.meterly.common.billing.BillingPeriod;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only lookups of tenant configuration owned by tenant-service and billing-service (resources,
 * the subscribed plan, issued invoices). Runs under RLS inside a tenant-bound transaction.
 */
@Repository
public class TenantCatalogRepository {

    public record Resource(UUID id, String name, String unitLabel, boolean active) {
    }

    public record PlanSummary(String code, String name, long includedUnits) {

        /** Shown when a tenant has no subscription covering the period (no allowance). */
        public static final PlanSummary NONE = new PlanSummary("NONE", "No active plan", 0);
    }

    private final JdbcClient jdbc;

    public TenantCatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** All resources of the tenant (active and archived), ordered by name. */
    public List<Resource> resources(UUID tenantId) {
        return jdbc.sql("""
                        SELECT id, name, unit_label, status
                          FROM metered_resource
                         WHERE tenant_id = :tenantId
                         ORDER BY name, id
                        """)
                .param("tenantId", tenantId)
                .query((rs, row) -> new Resource(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("unit_label"),
                        "ACTIVE".equals(rs.getString("status"))))
                .list();
    }

    public Optional<Resource> resource(UUID tenantId, UUID resourceId) {
        return jdbc.sql("""
                        SELECT id, name, unit_label, status
                          FROM metered_resource
                         WHERE tenant_id = :tenantId AND id = :resourceId
                        """)
                .param("tenantId", tenantId)
                .param("resourceId", resourceId)
                .query((rs, row) -> new Resource(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("unit_label"),
                        "ACTIVE".equals(rs.getString("status"))))
                .optional();
    }

    /**
     * The plan of the active subscription overlapping the period. Plan changes take effect at period
     * boundaries, so at most one subscription covers a period; the latest start wins defensively.
     */
    public Optional<PlanSummary> plan(UUID tenantId, BillingPeriod period) {
        return jdbc.sql("""
                        SELECT p.code, p.name, p.included_units
                          FROM subscription s
                          JOIN plan p ON p.id = s.plan_id
                         WHERE s.tenant_id = :tenantId
                           AND s.status = 'ACTIVE'
                           AND s.start_date < :periodEnd
                           AND (s.end_date IS NULL OR s.end_date > :periodStart)
                         ORDER BY s.start_date DESC
                         LIMIT 1
                        """)
                .param("tenantId", tenantId)
                .param("periodStart", period.start())
                .param("periodEnd", period.end())
                .query((rs, row) -> new PlanSummary(
                        rs.getString("code"), rs.getString("name"), rs.getLong("included_units")))
                .optional();
    }

    /** True once billing-service has issued the invoice for the period (events after that are late). */
    public boolean invoiceExists(UUID tenantId, BillingPeriod period) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM invoice WHERE tenant_id = :tenantId AND period_start = :periodStart)")
                .param("tenantId", tenantId)
                .param("periodStart", period.start())
                .query(Boolean.class)
                .single();
    }
}
