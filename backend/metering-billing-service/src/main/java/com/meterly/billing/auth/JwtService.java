package com.meterly.billing.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class JwtService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String HEADER = ENCODER.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final long tokenSeconds;

    public JwtService(
            ObjectMapper objectMapper,
            @Value("${meterly.auth.jwt-secret}") String secret,
            @Value("${meterly.auth.token-seconds}") long tokenSeconds
    ) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 bytes");
        }
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8).clone();
        this.tokenSeconds = tokenSeconds;
    }

    public String issue(AuthPrincipal principal) {
        try {
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", principal.email());
            claims.put("tenant_id", principal.tenantId().toString());
            claims.put("role", principal.role());
            claims.put("iat", Instant.now().getEpochSecond());
            claims.put("exp", Instant.now().plusSeconds(tokenSeconds).getEpochSecond());
            String payload = ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
            String unsigned = HEADER + "." + payload;
            return unsigned + "." + ENCODER.encodeToString(sign(unsigned));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not issue JWT", exception);
        }
    }

    public Optional<AuthPrincipal> verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3 || !MessageDigest.isEqual(
                    DECODER.decode(parts[2]), sign(parts[0] + "." + parts[1]))) {
                return Optional.empty();
            }
            Map<String, Object> claims = objectMapper.readValue(
                    DECODER.decode(parts[1]), new TypeReference<>() {});
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

    private byte[] sign(String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(content.getBytes(StandardCharsets.US_ASCII));
    }
}
