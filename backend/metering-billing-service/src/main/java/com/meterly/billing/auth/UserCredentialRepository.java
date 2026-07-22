package com.meterly.billing.auth;

import com.meterly.billing.tenant.TenantTransactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class UserCredentialRepository {
    private final JdbcTemplate jdbcTemplate;

    public UserCredentialRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @TenantTransactional
    public Optional<Credential> findByEmail(String email) {
        return jdbcTemplate.query("""
                SELECT tenant_id, email, role, password_hash
                FROM app_user WHERE lower(email) = lower(?)
                """,
                (resultSet, rowNumber) -> new Credential(
                        resultSet.getObject("tenant_id", UUID.class),
                        resultSet.getString("email"),
                        resultSet.getString("role"),
                        resultSet.getString("password_hash")
                ),
                email
        ).stream().findFirst();
    }

    public record Credential(UUID tenantId, String email, String role, String passwordHash) {
    }
}
