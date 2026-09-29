package com.meterly.tenant.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * tenant-service settings under {@code meterly.tenant.*}.
 *
 * <pre>
 * meterly.tenant.jwt.private-key-path          PKCS#8 PEM RSA private key (env JWT_PRIVATE_KEY_PATH)
 * meterly.tenant.jwt.public-key-path           optional SPKI PEM public key, checked against the private key (JWT_PUBLIC_KEY_PATH)
 * meterly.tenant.jwt.key-id                    kid of the signing key; RFC 7638 thumbprint when blank (JWT_KEY_ID)
 * meterly.tenant.jwt.additional-public-keys    retired/next public keys still published in the JWKS (key rotation)
 * meterly.tenant.jwt.allow-ephemeral-key       generate a throw-away key when none is configured (default: true unless profile prod)
 * meterly.tenant.jwt.access-token-ttl          access-token lifetime (default 15m)
 * meterly.tenant.throttle.*                    Redis token buckets for login and signup
 * meterly.tenant.bcrypt-strength               BCrypt cost factor for new password hashes (default 12)
 * </pre>
 */
@ConfigurationProperties("meterly.tenant")
public record TenantProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue Throttle throttle,
        @DefaultValue("12") int bcryptStrength) {

    public TenantProperties {
        if (bcryptStrength < 4 || bcryptStrength > 31) {
            throw new IllegalArgumentException("meterly.tenant.bcrypt-strength must be between 4 and 31");
        }
    }

    public record Jwt(
            String privateKeyPath,
            String publicKeyPath,
            String keyId,
            List<PublicKeyFile> additionalPublicKeys,
            Boolean allowEphemeralKey,
            @DefaultValue("15m") Duration accessTokenTtl) {

        public Jwt {
            additionalPublicKeys = additionalPublicKeys == null ? List.of() : List.copyOf(additionalPublicKeys);
            if (accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
                throw new IllegalArgumentException("meterly.tenant.jwt.access-token-ttl must be positive");
            }
        }
    }

    /** A public key published in the JWKS for verification only (for example the previous signing key). */
    public record PublicKeyFile(String path, String keyId) {
    }

    /**
     * Token-bucket limits. Each bucket holds {@code capacity} attempts and regains
     * {@code refillPerMinute} attempts per minute.
     */
    public record Throttle(Bucket loginIp, Bucket loginEmail, Bucket signupIp) {

        public Throttle {
            loginIp = loginIp == null ? new Bucket(50, 20) : loginIp;
            loginEmail = loginEmail == null ? new Bucket(10, 1) : loginEmail;
            signupIp = signupIp == null ? new Bucket(10, 2) : signupIp;
        }
    }

    public record Bucket(int capacity, double refillPerMinute) {

        public Bucket {
            if (capacity < 1 || refillPerMinute <= 0) {
                throw new IllegalArgumentException("Throttle buckets need capacity >= 1 and refill-per-minute > 0");
            }
        }

        public double refillPerSecond() {
            return refillPerMinute / 60.0;
        }
    }
}
