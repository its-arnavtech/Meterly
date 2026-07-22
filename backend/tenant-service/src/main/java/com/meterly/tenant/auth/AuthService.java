package com.meterly.tenant.auth;

import com.meterly.platform.auth.AuthPrincipal;
import com.meterly.platform.auth.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuthService {
    private final UserCredentialRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserCredentialRepository repository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(String email, String password) {
        UserCredentialRepository.Credential credential = repository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        if (!passwordEncoder.matches(password, credential.passwordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }
        AuthPrincipal principal = new AuthPrincipal(
                credential.email(), credential.tenantId(), credential.role());
        return new LoginResponse(
                jwtService.issue(principal), principal.tenantId(), principal.email(), principal.role());
    }

    public record LoginResponse(String accessToken, UUID tenantId, String email, String role) {
    }
}
