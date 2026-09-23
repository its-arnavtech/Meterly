package com.meterly.tenant.user;

import com.meterly.common.security.Role;
import java.time.Instant;
import java.util.UUID;

/** Contract "User" object: {@code { id, email, role, status, created_at }}. */
public record User(UUID id, String email, Role role, UserStatus status, Instant createdAt) {

    public enum UserStatus {
        ACTIVE,
        DISABLED
    }

    public boolean isActiveAdmin() {
        return role == Role.TENANT_ADMIN && status == UserStatus.ACTIVE;
    }
}
