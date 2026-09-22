package com.meterly.metering.counter;

import com.meterly.common.billing.BillingPeriod;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The transactional projection {@code usage_counter_state}: one row per counter, written in the same
 * transaction as the ledger row it counts. Writers must hold the counter's advisory lock
 * ({@link UsageLedgerRepository#lock}).
 */
@Repository
public class CounterStateRepository {

    private final JdbcClient jdbc;

    public CounterStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds a newly inserted ledger row to the counter and returns the new snapshot. */
    public CounterSnapshot increment(CounterKey key, long quantity, long ledgerId) {
        return jdbc.sql("""
                        INSERT INTO usage_counter_state (tenant_id, resource_id, period_start, total_units, ledger_version)
                        VALUES (:tenantId, :resourceId, :periodStart, :quantity, :ledgerId)
                        ON CONFLICT (tenant_id, resource_id, period_start) DO UPDATE
                           SET total_units    = usage_counter_state.total_units + EXCLUDED.total_units,
                               ledger_version = GREATEST(usage_counter_state.ledger_version, EXCLUDED.ledger_version)
                        RETURNING total_units, ledger_version
                        """)
                .param("tenantId", key.tenantId())
                .param("resourceId", key.resourceId())
                .param("periodStart", key.period().start())
                .param("quantity", quantity)
                .param("ledgerId", ledgerId)
                .query(CounterStateRepository::snapshot)
                .single();
    }

    public Optional<CounterSnapshot> find(CounterKey key) {
        return jdbc.sql("""
                        SELECT total_units, ledger_version
                          FROM usage_counter_state
                         WHERE tenant_id = :tenantId AND resource_id = :resourceId AND period_start = :periodStart
                        """)
                .param("tenantId", key.tenantId())
                .param("resourceId", key.resourceId())
                .param("periodStart", key.period().start())
                .query(CounterStateRepository::snapshot)
                .optional();
    }

    /** All projection rows of one tenant for a period, keyed by resource id. */
    public Map<UUID, CounterSnapshot> findForPeriod(UUID tenantId, BillingPeriod period) {
        Map<UUID, CounterSnapshot> snapshots = new HashMap<>();
        jdbc.sql("""
                        SELECT resource_id, total_units, ledger_version
                          FROM usage_counter_state
                         WHERE tenant_id = :tenantId AND period_start = :periodStart
                        """)
                .param("tenantId", tenantId)
                .param("periodStart", period.start())
                .query(rs -> {
                    snapshots.put(rs.getObject("resource_id", UUID.class), snapshot(rs, 0));
                });
        return snapshots;
    }

    /** Replaces the counter with a snapshot recomputed from the ledger (reconciliation). */
    public void overwrite(CounterKey key, CounterSnapshot snapshot) {
        jdbc.sql("""
                        INSERT INTO usage_counter_state (tenant_id, resource_id, period_start, total_units, ledger_version)
                        VALUES (:tenantId, :resourceId, :periodStart, :totalUnits, :ledgerVersion)
                        ON CONFLICT (tenant_id, resource_id, period_start) DO UPDATE
                           SET total_units = EXCLUDED.total_units, ledger_version = EXCLUDED.ledger_version
                        """)
                .param("tenantId", key.tenantId())
                .param("resourceId", key.resourceId())
                .param("periodStart", key.period().start())
                .param("totalUnits", snapshot.totalUnits())
                .param("ledgerVersion", snapshot.ledgerVersion())
                .update();
    }

    private static CounterSnapshot snapshot(ResultSet rs, int row) throws SQLException {
        return new CounterSnapshot(rs.getLong("total_units"), rs.getLong("ledger_version"));
    }
}
