package com.meterly.tenant.auth;

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

    public Optional<Credential> findByEmail(String email) {
        return jdbcTemplate.query("SELECT * FROM auth_lookup_user(?)",
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
