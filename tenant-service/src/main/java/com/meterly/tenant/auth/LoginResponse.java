package com.meterly.tenant.auth;

import com.meterly.common.security.Role;
import java.util.UUID;

/** Body of {@code POST /v1/auth/login} and {@code POST /v1/auth/refresh}. */
public record LoginResponse(String accessToken, String tokenType, long expiresIn, AuthenticatedUser user) {

    public static final String BEARER = "Bearer";

    public record AuthenticatedUser(UUID id, String email, Role role, UUID tenantId, String tenantName) {
    }

    @Override
    public String toString() {
        return "LoginResponse[user=" + user.id() + ", accessToken=<redacted>]";
    }
}
