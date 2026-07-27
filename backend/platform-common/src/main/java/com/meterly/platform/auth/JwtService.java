package com.meterly.platform.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

public final class JwtService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String HEADER = ENCODER.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final long tokenSeconds;
    private final JwtDecoder externalDecoder;

    public JwtService(ObjectMapper objectMapper, String secret, long tokenSeconds) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 bytes");
        }
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8).clone();
        this.tokenSeconds = tokenSeconds;
        this.externalDecoder = null;
    }

    private JwtService(ObjectMapper objectMapper, JwtDecoder externalDecoder) {
        this.objectMapper = objectMapper;
        this.secret = new byte[0];
        this.tokenSeconds = 0;
        this.externalDecoder = externalDecoder;
    }

    public static JwtService forIssuer(ObjectMapper objectMapper, String issuerUri, String jwkSetUri) {
        if (issuerUri == null || issuerUri.isBlank() || jwkSetUri == null || jwkSetUri.isBlank()) {
            throw new IllegalArgumentException("Keycloak issuer and JWK set URIs are both required");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(issuerUri)
        ));
        return new JwtService(objectMapper, decoder);
    }

    public String issue(AuthPrincipal principal) {
        if (externalDecoder != null) {
            throw new IllegalStateException("Tokens are issued by the configured external identity provider");
        }
        try {
            Instant now = Instant.now();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", principal.email());
            claims.put("tenant_id", principal.tenantId().toString());
            claims.put("role", principal.role());
            claims.put("iat", now.getEpochSecond());
            claims.put("exp", now.plusSeconds(tokenSeconds).getEpochSecond());
            String payload = ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
            String unsigned = HEADER + "." + payload;
            return unsigned + "." + ENCODER.encodeToString(sign(unsigned));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not issue JWT", exception);
        }
    }

    public Optional<AuthPrincipal> verify(String token) {
        if (externalDecoder != null) {
            return verifyExternal(token);
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3 || !MessageDigest.isEqual(
                    DECODER.decode(parts[2]), sign(parts[0] + "." + parts[1]))) {
                return Optional.empty();
            }
            Map<String, Object> claims = objectMapper.readValue(
                    DECODER.decode(parts[1]), new TypeReference<>() { });
            Number expiration = (Number) claims.get("exp");
            if (expiration == null || expiration.longValue() <= Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            return Optional.of(new AuthPrincipal(
                    (String) claims.get("sub"),
                    UUID.fromString((String) claims.get("tenant_id")),
                    (String) claims.get("role")
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<AuthPrincipal> verifyExternal(String token) {
        try {
            Jwt jwt = externalDecoder.decode(token);
            String email = jwt.getClaimAsString("email");
            if (email == null || email.isBlank()) {
                email = jwt.getSubject();
            }
            String tenantId = jwt.getClaimAsString("tenant_id");
            String role = jwt.getClaimAsString("role");
            if (email == null || tenantId == null || role == null
                    || !List.of("TENANT_ADMIN", "TENANT_USER").contains(role)) {
                return Optional.empty();
            }
            return Optional.of(new AuthPrincipal(email, UUID.fromString(tenantId), role));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private byte[] sign(String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(content.getBytes(StandardCharsets.US_ASCII));
    }
}
