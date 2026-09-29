package com.meterly.tenant.user;

import com.meterly.common.security.Role;
import java.util.UUID;

/**
 * Login lookup result from the SECURITY DEFINER function {@code auth_find_user_by_email}: everything needed
 * to verify a password and then continue under RLS as the user's tenant.
 */
public record Credentials(
        UUID userId,
        UUID tenantId,
        String email,
        Role role,
        String passwordHash,
        boolean userActive,
        boolean tenantActive,
        String tenantName) {

    public boolean canSignIn() {
        return userActive && tenantActive;
    }

    @Override
    public String toString() {
        return "Credentials[userId=" + userId + ", tenantId=" + tenantId + ", passwordHash=<redacted>]";
    }
}
