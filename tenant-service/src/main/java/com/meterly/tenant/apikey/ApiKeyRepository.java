package com.meterly.tenant.apikey;

import com.meterly.tenant.support.JdbcColumns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code api_key} table (RLS-scoped) plus the pre-authentication hash lookup. */
@Repository
public class ApiKeyRepository {

    private final JdbcClient jdbc;

    public ApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Result of the SECURITY DEFINER lookup {@code auth_find_api_key}. */
    public record KeyOwner(UUID apiKeyId, UUID tenantId, boolean revoked, boolean tenantActive) {
    }

    public Optional<KeyOwner> findByHash(String keyHash) {
        return jdbc.sql("""
                        SELECT api_key_id, tenant_id, revoked, tenant_status
                        FROM auth_find_api_key(:keyHash)
                        """)
                .param("keyHash", keyHash)
                .query((rs, row) -> new KeyOwner(
                        JdbcColumns.uuid(rs, "api_key_id"),
                        JdbcColumns.uuid(rs, "tenant_id"),
                        rs.getBoolean("revoked"),
                        "ACTIVE".equals(rs.getString("tenant_status"))))
                .optional();
    }

    public void insert(UUID id, UUID tenantId, String name, String prefix, String keyHash, UUID createdBy) {
        jdbc.sql("""
                        INSERT INTO api_key (id, tenant_id, name, prefix, key_hash, created_by)
                        VALUES (:id, :tenantId, :name, :prefix, :keyHash, :createdBy)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("name", name)
                .param("prefix", prefix)
                .param("keyHash", keyHash)
                .param("createdBy", createdBy)
                .update();
    }

    public Optional<ApiKey> find(UUID id) {
        return jdbc.sql("""
                        SELECT id, name, prefix, created_at, last_used_at, revoked_at
                        FROM api_key
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(ApiKeyRepository::map)
                .optional();
    }

    public List<ApiKey> list() {
        return jdbc.sql("""
                        SELECT id, name, prefix, created_at, last_used_at, revoked_at
                        FROM api_key
                        ORDER BY created_at DESC, id
                        """)
                .query(ApiKeyRepository::map)
                .list();
    }

    /** Idempotent: an already revoked key keeps its original {@code revoked_at}. */
    public boolean revoke(UUID id) {
        return jdbc.sql("""
                        UPDATE api_key
                        SET revoked_at = coalesce(revoked_at, now())
                        WHERE id = :id
                        """)
                .param("id", id)
                .update() == 1;
    }

    public boolean isUsable(UUID id) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM api_key
                        WHERE id = :id AND revoked_at IS NULL
                        """)
                .param("id", id)
                .query(Long.class)
                .single() == 1;
    }

    /** Records usage at most once per minute per key, so hot keys do not turn every verify into a write. */
    public void touchLastUsed(UUID id) {
        jdbc.sql("""
                        UPDATE api_key
                        SET last_used_at = now()
                        WHERE id = :id
                          AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')
                        """)
                .param("id", id)
                .update();
    }

    private static ApiKey map(ResultSet rs, int row) throws SQLException {
        return new ApiKey(
                JdbcColumns.uuid(rs, "id"),
                rs.getString("name"),
                rs.getString("prefix"),
                JdbcColumns.instant(rs, "created_at"),
                JdbcColumns.instant(rs, "last_used_at"),
                JdbcColumns.instant(rs, "revoked_at"));
    }
}
