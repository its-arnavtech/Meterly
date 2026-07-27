package com.meterly.tenant.auth;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuthService {
    private final IdentityProvider identityProvider;

    public AuthService(IdentityProvider identityProvider) {
        this.identityProvider = identityProvider;
    }

    public LoginResponse login(String email, String password) {
        IdentityProvider.IdentitySession session = identityProvider.login(email, password);
        return new LoginResponse(
                session.accessToken(), session.tenantId(), session.email(), session.role());
    }

    public record LoginResponse(String accessToken, UUID tenantId, String email, String role) {
    }
}
