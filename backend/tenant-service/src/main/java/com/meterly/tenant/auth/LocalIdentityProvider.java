package com.meterly.tenant.auth;

import com.meterly.platform.auth.AuthPrincipal;
import com.meterly.platform.auth.JwtService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "meterly.auth.provider", havingValue = "local", matchIfMissing = true)
final class LocalIdentityProvider implements IdentityProvider {
    private final UserCredentialRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    LocalIdentityProvider(
            UserCredentialRepository repository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Override
    public IdentitySession login(String email, String password) {
        UserCredentialRepository.Credential credential = repository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        if (!passwordEncoder.matches(password, credential.passwordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }
        AuthPrincipal principal = new AuthPrincipal(
                credential.email(), credential.tenantId(), credential.role());
        return new IdentitySession(
                jwtService.issue(principal), principal.tenantId(), principal.email(), principal.role());
    }

    @Override
    public String createUser(String email, String password, UUID tenantId, String role) {
        return null;
    }

    @Override
    public void deleteUser(String externalUserId) {
        // Local users are owned solely by PostgreSQL.
    }
}
