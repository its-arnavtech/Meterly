package com.meterly.tenant.auth;

import com.meterly.tenant.support.JdbcColumns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Refresh tokens are stored only as SHA-256 hashes. Tokens of one login session share a {@code family_id};
 * each rotation revokes the presented token and links it to its successor via {@code replaced_by}.
 */
@Repository
public class RefreshTokenRepository {

    private final JdbcClient jdbc;

    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record StoredToken(UUID id, UUID tenantId, UUID userId, UUID familyId,
                              Instant expiresAt, Instant revokedAt, UUID replacedBy) {

        public boolean wasRotated() {
            return revokedAt != null && replacedBy != null;
        }
    }

    /** Pre-authentication lookup through the SECURITY DEFINER function (the tenant is not yet known). */
    public Optional<StoredToken> findByHash(String tokenHash) {
        return jdbc.sql("""
                        SELECT token_id AS id, tenant_id, user_id, family_id, expires_at, revoked_at, replaced_by
                        FROM auth_find_refresh_token(:tokenHash)
                        """)
                .param("tokenHash", tokenHash)
                .query(RefreshTokenRepository::map)
                .optional();
    }

    /** Locks the token row for the rest of the tenant transaction, serializing concurrent exchanges. */
    public Optional<StoredToken> lock(UUID id) {
        return jdbc.sql("""
                        SELECT id, tenant_id, user_id, family_id, expires_at, revoked_at, replaced_by
                        FROM refresh_token
                        WHERE id = :id
                        FOR UPDATE
                        """)
                .param("id", id)
                .query(RefreshTokenRepository::map)
                .optional();
    }

    public void insert(UUID id, UUID tenantId, UUID userId, String tokenHash, UUID familyId, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO refresh_token (id, tenant_id, user_id, token_hash, family_id, expires_at)
                        VALUES (:id, :tenantId, :userId, :tokenHash, :familyId, :expiresAt)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .param("tokenHash", tokenHash)
                .param("familyId", familyId)
                .param("expiresAt", JdbcColumns.timestamp(expiresAt))
                .update();
    }

    public void markRotated(UUID id, UUID replacedBy) {
        jdbc.sql("""
                        UPDATE refresh_token
                        SET revoked_at = now(), replaced_by = :replacedBy
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("replacedBy", replacedBy)
                .update();
    }

    public int revokeFamily(UUID familyId) {
        return jdbc.sql("""
                        UPDATE refresh_token
                        SET revoked_at = now()
                        WHERE family_id = :familyId
                          AND revoked_at IS NULL
                        """)
                .param("familyId", familyId)
                .update();
    }

    public int revokeAllForUser(UUID userId) {
        return jdbc.sql("""
                        UPDATE refresh_token
                        SET revoked_at = now()
                        WHERE user_id = :userId
                          AND revoked_at IS NULL
                        """)
                .param("userId", userId)
                .update();
    }

    private static StoredToken map(ResultSet rs, int row) throws SQLException {
        return new StoredToken(
                JdbcColumns.uuid(rs, "id"),
                JdbcColumns.uuid(rs, "tenant_id"),
                JdbcColumns.uuid(rs, "user_id"),
                JdbcColumns.uuid(rs, "family_id"),
                JdbcColumns.instant(rs, "expires_at"),
                JdbcColumns.instant(rs, "revoked_at"),
                JdbcColumns.uuid(rs, "replaced_by"));
    }
}
