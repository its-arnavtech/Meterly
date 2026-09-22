package com.meterly.tenant.account;

import com.meterly.tenant.support.JdbcColumns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code tenant} table. RLS limits every statement to the row whose id equals the bound
 * {@code app.tenant_id}, so callers must run inside a tenant-bound transaction.
 */
@Repository
public class TenantRepository {

    private final JdbcClient jdbc;

    public TenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String name) {
        jdbc.sql("""
                        INSERT INTO tenant (id, name)
                        VALUES (:id, :name)
                        """)
                .param("id", id)
                .param("name", name)
                .update();
    }

    public Optional<Tenant> find(UUID id) {
        return jdbc.sql("""
                        SELECT id, name, status, created_at
                        FROM tenant
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(TenantRepository::map)
                .optional();
    }

    public boolean rename(UUID id, String name) {
        return jdbc.sql("""
                        UPDATE tenant
                        SET name = :name
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("name", name)
                .update() == 1;
    }

    /**
     * Takes a row lock on the tenant for the rest of the transaction. Used to serialize invariant checks
     * that span several rows (last active admin, one pending plan change).
     */
    public boolean lock(UUID id) {
        return jdbc.sql("""
                        SELECT id
                        FROM tenant
                        WHERE id = :id
                        FOR UPDATE
                        """)
                .param("id", id)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    private static Tenant map(ResultSet rs, int row) throws SQLException {
        return new Tenant(
                JdbcColumns.uuid(rs, "id"),
                rs.getString("name"),
                Tenant.TenantStatus.valueOf(rs.getString("status")),
                JdbcColumns.instant(rs, "created_at"));
    }
}
