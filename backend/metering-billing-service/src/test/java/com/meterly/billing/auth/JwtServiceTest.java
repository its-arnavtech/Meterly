package com.meterly.billing.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {
    private final JwtService jwtService = new JwtService(
            new ObjectMapper(), "test-secret-with-at-least-thirty-two-bytes", 60);

    @Test
    void issuedTokenRoundTripsTenantAndRole() {
        AuthPrincipal expected = new AuthPrincipal(
                "admin@meterly.local",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "TENANT_ADMIN");

        assertThat(jwtService.verify(jwtService.issue(expected))).contains(expected);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtService.issue(new AuthPrincipal(
                "admin@meterly.local", UUID.randomUUID(), "TENANT_ADMIN"));
        char replacement = token.endsWith("A") ? 'B' : 'A';

        assertThat(jwtService.verify(token.substring(0, token.length() - 1) + replacement)).isEmpty();
    }
}
