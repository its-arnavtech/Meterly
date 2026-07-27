package com.meterly.metering.usage;

import com.meterly.platform.tenant.TenantTransactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ReconciliationRepository {
    private final JdbcTemplate jdbcTemplate;

    public ReconciliationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<UUID> listActiveTenantIds() {
        return jdbcTemplate.queryForList("SELECT * FROM system_active_tenant_ids()", UUID.class);
    }

    @TenantTransactional
    public List<ResourceTotal> totalsForPeriod(Instant periodStart, Instant periodEnd) {
        return jdbcTemplate.query("""
                SELECT resource.id AS resource_id, COALESCE(SUM(event.quantity), 0) AS total_units
                FROM metered_resource resource
                LEFT JOIN usage_event event
                  ON event.resource_id = resource.id
                 AND event.occurred_at >= ?
                 AND event.occurred_at < ?
                GROUP BY resource.id
                ORDER BY resource.id
                """, (resultSet, row) -> new ResourceTotal(
                resultSet.getObject("resource_id", UUID.class),
                resultSet.getLong("total_units")
        ), Timestamp.from(periodStart), Timestamp.from(periodEnd));
    }

    public record ResourceTotal(UUID resourceId, long totalUnits) { }
}
