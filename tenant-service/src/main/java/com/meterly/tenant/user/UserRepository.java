package com.meterly.tenant.user;

import com.meterly.common.security.Role;
import com.meterly.tenant.support.JdbcColumns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code app_user} table (RLS-scoped) plus the pre-authentication email lookup. */
@Repository
public class UserRepository {

    private static final String USER_COLUMNS = "id, email, role, status, created_at";

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Finds a user by email across all tenants through the SECURITY DEFINER function. This is the only way
     * to read users before the tenant is known (login, global email uniqueness); it needs no transaction.
     */
    public Optional<Credentials> findCredentials(String normalizedEmail) {
        return jdbc.sql("""
                        SELECT user_id, tenant_id, email, role, password_hash, user_status, tenant_status, tenant_name
                        FROM auth_find_user_by_email(:email)
                        """)
                .param("email", normalizedEmail)
                .query((rs, row) -> new Credentials(
                        JdbcColumns.uuid(rs, "user_id"),
                        JdbcColumns.uuid(rs, "tenant_id"),
                        rs.getString("email"),
                        Role.valueOf(rs.getString("role")),
                        rs.getString("password_hash"),
                        "ACTIVE".equals(rs.getString("user_status")),
                        "ACTIVE".equals(rs.getString("tenant_status")),
                        rs.getString("tenant_name")))
                .optional();
    }

    /** Emails are globally unique (login is by email alone), so the check spans every tenant. */
    public boolean emailTaken(String normalizedEmail) {
        return findCredentials(normalizedEmail).isPresent();
    }

    public void insert(UUID id, UUID tenantId, String email, Role role, String passwordHash) {
        jdbc.sql("""
                        INSERT INTO app_user (id, tenant_id, email, role, password_hash)
                        VALUES (:id, :tenantId, :email, :role, :passwordHash)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("email", email)
                .param("role", role.name())
                .param("passwordHash", passwordHash)
                .update();
    }

    public Optional<User> find(UUID id) {
        return jdbc.sql("SELECT " + USER_COLUMNS + " FROM app_user WHERE id = :id")
                .param("id", id)
                .query(UserRepository::map)
                .optional();
    }

    public List<User> list() {
        return jdbc.sql("SELECT " + USER_COLUMNS + " FROM app_user ORDER BY created_at, id")
                .query(UserRepository::map)
                .list();
    }

    public void update(UUID id, Role role, User.UserStatus status) {
        jdbc.sql("""
                        UPDATE app_user
                        SET role = :role, status = :status
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("role", role.name())
                .param("status", status.name())
                .update();
    }

    public long countActiveAdmins() {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM app_user
                        WHERE role = 'TENANT_ADMIN' AND status = 'ACTIVE'
                        """)
                .query(Long.class)
                .single();
    }

    public List<String> activeAdminEmails() {
        return jdbc.sql("""
                        SELECT email
                        FROM app_user
                        WHERE role = 'TENANT_ADMIN' AND status = 'ACTIVE'
                        ORDER BY email
                        """)
                .query(String.class)
                .list();
    }

    /** The user's current role/status and tenant, used to re-check a caller whose JWT may be stale. */
    public Optional<Account> findAccount(UUID id) {
        return jdbc.sql("""
                        SELECT u.id, u.tenant_id, u.email, u.role, u.status, t.status AS tenant_status, t.name AS tenant_name
                        FROM app_user u
                        JOIN tenant t ON t.id = u.tenant_id
                        WHERE u.id = :id
                        """)
                .param("id", id)
                .query((rs, row) -> new Account(
                        JdbcColumns.uuid(rs, "id"),
                        JdbcColumns.uuid(rs, "tenant_id"),
                        rs.getString("email"),
                        Role.valueOf(rs.getString("role")),
                        "ACTIVE".equals(rs.getString("status")) && "ACTIVE".equals(rs.getString("tenant_status")),
                        rs.getString("tenant_name")))
                .optional();
    }

    /** A user joined with its tenant; {@code active} means both the user and the tenant are active. */
    public record Account(UUID userId, UUID tenantId, String email, Role role, boolean active, String tenantName) {
    }

    private static User map(ResultSet rs, int row) throws SQLException {
        return new User(
                JdbcColumns.uuid(rs, "id"),
                rs.getString("email"),
                Role.valueOf(rs.getString("role")),
                User.UserStatus.valueOf(rs.getString("status")),
                JdbcColumns.instant(rs, "created_at"));
    }
}
