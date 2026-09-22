package com.meterly.tenant.subscription;

import com.meterly.tenant.support.JdbcColumns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code subscription} table (RLS-scoped). The exclusion constraint {@code subscription_no_overlap}
 * guarantees at most one ACTIVE subscription per day, so statement order matters when changing plans:
 * cancel the pending subscription, then close the current one, then insert the new one.
 */
@Repository
public class SubscriptionRepository {

    private final JdbcClient jdbc;

    public SubscriptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A subscription row; {@code planId} is resolved to a full plan by the service. */
    public record Row(UUID id, UUID planId, LocalDate startDate, LocalDate endDate, String status) {
    }

    public UUID insert(UUID tenantId, UUID planId, LocalDate startDate) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO subscription (id, tenant_id, plan_id, start_date)
                        VALUES (:id, :tenantId, :planId, :startDate)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("planId", planId)
                .param("startDate", startDate)
                .update();
        return id;
    }

    /** The ACTIVE subscription covering {@code day}. */
    public Optional<Row> findCovering(LocalDate day) {
        return jdbc.sql("""
                        SELECT id, plan_id, start_date, end_date, status
                        FROM subscription
                        WHERE status = 'ACTIVE'
                          AND start_date <= :day
                          AND (end_date IS NULL OR end_date > :day)
                        """)
                .param("day", day)
                .query(SubscriptionRepository::map)
                .optional();
    }

    /** The earliest ACTIVE subscription starting after {@code day} (a pending plan change). */
    public Optional<Row> findStartingAfter(LocalDate day) {
        return jdbc.sql("""
                        SELECT id, plan_id, start_date, end_date, status
                        FROM subscription
                        WHERE status = 'ACTIVE'
                          AND start_date > :day
                        ORDER BY start_date
                        LIMIT 1
                        """)
                .param("day", day)
                .query(SubscriptionRepository::map)
                .optional();
    }

    public int cancelStartingAfter(LocalDate day) {
        return jdbc.sql("""
                        UPDATE subscription
                        SET status = 'CANCELED'
                        WHERE status = 'ACTIVE'
                          AND start_date > :day
                        """)
                .param("day", day)
                .update();
    }

    public void setEndDate(UUID id, LocalDate endDate) {
        jdbc.sql("""
                        UPDATE subscription
                        SET end_date = :endDate
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("endDate", endDate, Types.DATE)
                .update();
    }

    private static Row map(ResultSet rs, int row) throws SQLException {
        return new Row(
                JdbcColumns.uuid(rs, "id"),
                JdbcColumns.uuid(rs, "plan_id"),
                JdbcColumns.date(rs, "start_date"),
                JdbcColumns.date(rs, "end_date"),
                rs.getString("status"));
    }
}
