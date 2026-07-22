package com.meterly.billing.auth;

import com.meterly.billing.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuthService {
    private final UserCredentialRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UUID pocTenantId;
    private final String ingestionApiKey;

    public AuthService(
            UserCredentialRepository repository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            @Value("${meterly.auth.tenant-id}") UUID pocTenantId,
            @Value("${meterly.ingestion-api-key}") String ingestionApiKey
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.pocTenantId = pocTenantId;
        this.ingestionApiKey = ingestionApiKey;
    }

    public LoginResponse login(String email, String password) {
        UserCredentialRepository.Credential credential;
        try (TenantContext.Scope ignored = TenantContext.open(pocTenantId)) {
            credential = repository.findByEmail(email)
                    .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        }
        if (!passwordEncoder.matches(password, credential.passwordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }
        AuthPrincipal principal = new AuthPrincipal(
                credential.email(), credential.tenantId(), credential.role());
        return new LoginResponse(
                jwtService.issue(principal), principal.tenantId(), principal.email(), principal.role(), ingestionApiKey);
    }

    public record LoginResponse(
            String accessToken,
            UUID tenantId,
            String email,
            String role,
            String ingestionApiKey
    ) {
    }
}
