package com.meterly.testsupport;

import com.meterly.common.autoconfigure.MeterlySecurityAutoConfiguration;
import com.meterly.common.security.MeterlyClaims;
import com.meterly.common.security.Role;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Mints RS256 tokens shaped exactly like tenant-service's (same claims, issuer, audience) with a
 * per-JVM test key, and provides the matching decoder. Import {@link TestJwtConfiguration} in a
 * {@code @SpringBootTest} to make the service under test trust these tokens.
 */
public final class TestJwt {

    private static final RSAKey KEY = generateKey();
    private static final JwtEncoder ENCODER = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY)));

    private TestJwt() {
    }

    public static String token(UUID tenantId, UUID userId, Role role) {
        return token(tenantId, userId, role, "user-" + userId + "@test.meterly", Duration.ofMinutes(15));
    }

    public static String token(UUID tenantId, UUID userId, Role role, String email, Duration ttl) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(MeterlyClaims.DEFAULT_ISSUER)
                .audience(List.of(MeterlyClaims.DEFAULT_AUDIENCE))
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim(MeterlyClaims.TENANT_ID, tenantId.toString())
                .claim(MeterlyClaims.ROLE, role.name())
                .claim(MeterlyClaims.EMAIL, email)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY.getKeyID()).build();
        return ENCODER.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public static String bearer(UUID tenantId, UUID userId, Role role) {
        return "Bearer " + token(tenantId, userId, role);
    }

    public static JwtDecoder decoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(MeterlySecurityAutoConfiguration.meterlyTokenValidator(
                    MeterlyClaims.DEFAULT_ISSUER, MeterlyClaims.DEFAULT_AUDIENCE));
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JWKSet publicJwkSet() {
        return new JWKSet(KEY.toPublicJWK());
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
