package com.meterly.common.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authenticated end user, derived from a validated Meterly JWT. Controllers can declare a
 * {@code CurrentUser} parameter and it is resolved automatically (see {@code CurrentUserArgumentResolver}).
 */
public record CurrentUser(UUID userId, UUID tenantId, String email, Role role) {

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString(MeterlyClaims.TENANT_ID)),
                jwt.getClaimAsString(MeterlyClaims.EMAIL),
                Role.valueOf(jwt.getClaimAsString(MeterlyClaims.ROLE)));
    }

    public boolean isAdmin() {
        return role == Role.TENANT_ADMIN;
    }
}
