package com.meterly.tenant.jwt;

import com.meterly.common.security.MeterlyClaims;
import com.meterly.common.security.Role;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Issues RS256 access tokens carrying exactly the contract claims:
 * {@code iss, aud[], sub, iat, exp, tenant_id, role, email}, with the signing key's {@code kid} in the header.
 *
 * <p>The payload is written as a plain JSON object rather than through {@code JWTClaimsSet}, because the
 * latter collapses a single audience into a string while the contract specifies an array.
 */
public class AccessTokenIssuer {

    private final JwtKeyMaterial keys;
    private final JWSSigner signer;
    private final String issuer;
    private final String audience;
    private final Duration ttl;
    private final Clock clock;

    public AccessTokenIssuer(JwtKeyMaterial keys, String issuer, String audience, Duration ttl, Clock clock) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.audience = Objects.requireNonNull(audience, "audience");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
        try {
            this.signer = new RSASSASigner(keys.signingKey());
        } catch (JOSEException e) {
            throw new IllegalStateException("Invalid RSA signing key", e);
        }
    }

    /** The token's subject: the authenticated user and the tenant it belongs to. */
    public record Subject(UUID userId, UUID tenantId, Role role, String email) {

        public Subject {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(email, "email");
        }
    }

    public record IssuedToken(String value, Instant expiresAt, long expiresInSeconds) {
    }

    public IssuedToken issue(Subject subject) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", List.of(audience));
        claims.put("sub", subject.userId().toString());
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put(MeterlyClaims.TENANT_ID, subject.tenantId().toString());
        claims.put(MeterlyClaims.ROLE, subject.role().name());
        claims.put(MeterlyClaims.EMAIL, subject.email());

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(keys.signingKeyId())
                .type(JOSEObjectType.JWT)
                .build();
        JWSObject token = new JWSObject(header, new Payload(claims));
        try {
            token.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Access-token signing failed", e);
        }
        return new IssuedToken(token.serialize(), expiresAt, ttl.toSeconds());
    }

    public Duration ttl() {
        return ttl;
    }
}
