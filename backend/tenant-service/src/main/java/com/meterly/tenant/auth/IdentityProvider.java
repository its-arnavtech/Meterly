package com.meterly.tenant.auth;

import java.util.UUID;

public interface IdentityProvider {
    IdentitySession login(String email, String password);

    String createUser(String email, String password, UUID tenantId, String role);

    void deleteUser(String externalUserId);

    record IdentitySession(String accessToken, UUID tenantId, String email, String role) {
    }
}
