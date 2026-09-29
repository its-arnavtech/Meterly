package com.meterly.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import com.meterly.common.autoconfigure.MeterlySecurityAutoConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtValidationTest {
    @Test
    void rejectsInvalidTenantSubjectRoleAndMissingExpiry() {
        var validator = MeterlySecurityAutoConfiguration.meterlyTokenValidator("meterly", "meterly-api");
        assertThat(validator.validate(token().build()).hasErrors()).isFalse();
        assertThat(validator.validate(token().claim("tenant_id", "bad").build()).hasErrors()).isTrue();
        assertThat(validator.validate(token().subject("bad").build()).hasErrors()).isTrue();
        assertThat(validator.validate(token().claim("role", "INTERNAL").build()).hasErrors()).isTrue();
        assertThat(validator.validate(token().claims(c -> c.remove("exp")).build()).hasErrors()).isTrue();
        assertThat(validator.validate(token().audience(List.of("other")).build()).hasErrors()).isTrue();
    }
    private Jwt.Builder token() {
        return Jwt.withTokenValue("test").header("alg", "RS256").issuer("meterly")
                .audience(List.of("meterly-api")).subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim("tenant_id", UUID.randomUUID().toString()).claim("role", "TENANT_ADMIN");
    }
}
