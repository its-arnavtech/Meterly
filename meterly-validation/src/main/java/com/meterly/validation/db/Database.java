package com.meterly.validation.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Plain JDBC access as {@code meterly_app} (the RLS-enforced role every service connects as; see
 * deploy/postgres/initdb/01-roles.sh). Mirrors the pattern {@code RowLevelSecurityIT} proves against
 * Spring's {@code TenantAwareTransactionManager}: bind {@code app.tenant_id} with
 * {@code set_config(..., true)} ("true" = local to the current transaction) right after a transaction
 * starts, run the query with <em>no</em> {@code WHERE tenant_id = ...} clause, then end the
 * transaction. Passing a {@code null} tenant runs the block with no tenant bound at all.
 */
public final class Database implements AutoCloseable {

    /** One row from an unfiltered {@code SELECT count(*), sum(quantity) FROM usage_event WHERE resource_id = ?}. */
    public record CountSum(long count, long sum) {
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final Connection connection;

    private Database(Connection connection) {
        this.connection = connection;
    }

    public static Database connect(String jdbcUrl, String user, String password) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
        connection.setAutoCommit(false);
        return new Database(connection);
    }

    /**
     * Runs {@code work} inside one transaction with {@code app.tenant_id} bound to {@code tenantId}
     * ({@code null} means: run with no tenant bound at all, proving the "zero rows visible" case).
     */
    public <T> T runAsTenant(UUID tenantId, SqlWork<T> work) throws SQLException {
        try (PreparedStatement bind = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            bind.setString(1, tenantId == null ? "" : tenantId.toString());
            bind.execute();
            T result = work.apply(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        }
    }

    /** Every distinct {@code tenant_id} visible in {@code usage_event} with no WHERE clause at all. */
    public Set<UUID> distinctLedgerTenantIds(Connection c) throws SQLException {
        Set<UUID> ids = new LinkedHashSet<>();
        try (var stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT DISTINCT tenant_id FROM usage_event")) {
            while (rs.next()) {
                ids.add(rs.getObject(1, UUID.class));
            }
        }
        return ids;
    }

    public CountSum countAndSumForResource(Connection c, UUID resourceId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*), coalesce(sum(quantity), 0) FROM usage_event WHERE resource_id = ?")) {
            ps.setObject(1, resourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new CountSum(rs.getLong(1), rs.getLong(2));
            }
        }
    }

    public long countTenants(Connection c) throws SQLException {
        try (var stmt = c.createStatement(); ResultSet rs = stmt.executeQuery("SELECT count(*) FROM tenant")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
