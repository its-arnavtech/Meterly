package com.meterly.tenant.jwt;

import com.meterly.tenant.config.TenantProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the access-token signing key from PEM files, or generates an ephemeral one for local development.
 *
 * <p>Rules: a configured private key always wins; a configured public key must match it. With no private
 * key, an ephemeral 2048-bit key is generated only when explicitly allowed (the default outside the
 * {@code prod} profile) and a warning is logged, because such tokens die with the process and cannot be
 * validated by other replicas. Otherwise startup fails.
 */
public final class JwtKeyLoader {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyLoader.class);
    private static final int EPHEMERAL_KEY_BITS = 2048;

    private JwtKeyLoader() {
    }

    public static JwtKeyMaterial load(TenantProperties.Jwt config, boolean allowEphemeralKey) {
        List<RSAKey> additionalKeys = loadAdditionalKeys(config.additionalPublicKeys());
        if (isBlank(config.privateKeyPath())) {
            if (!isBlank(config.publicKeyPath())) {
                throw new IllegalStateException("meterly.tenant.jwt.public-key-path is set without a private key; "
                        + "set JWT_PRIVATE_KEY_PATH as well");
            }
            if (!allowEphemeralKey) {
                throw new IllegalStateException("No JWT signing key configured: set JWT_PRIVATE_KEY_PATH "
                        + "(meterly.tenant.jwt.private-key-path) or, for local development only, "
                        + "meterly.tenant.jwt.allow-ephemeral-key=true");
            }
            RSAKey ephemeral = generateEphemeralKey(config.keyId());
            log.warn("Generated an ephemeral RSA signing key (kid={}). Access tokens will not survive a restart "
                    + "and other replicas cannot validate them; configure JWT_PRIVATE_KEY_PATH for shared keys.",
                    ephemeral.getKeyID());
            return new JwtKeyMaterial(ephemeral, additionalKeys, true);
        }

        RSAPrivateCrtKey privateKey = PemKeys.readPrivateKey(Path.of(config.privateKeyPath()));
        RSAPublicKey publicKey = PemKeys.publicKeyOf(privateKey);
        if (!isBlank(config.publicKeyPath())) {
            RSAPublicKey configured = PemKeys.readPublicKey(Path.of(config.publicKeyPath()));
            if (!configured.getModulus().equals(publicKey.getModulus())
                    || !configured.getPublicExponent().equals(publicKey.getPublicExponent())) {
                throw new IllegalStateException("JWT public key " + config.publicKeyPath()
                        + " does not belong to the configured private key");
            }
        }
        RSAKey signingKey = withKeyId(new RSAKey.Builder(publicKey).privateKey(privateKey), config.keyId());
        log.info("Loaded RSA signing key kid={} ({} bits) and {} additional verification key(s)",
                signingKey.getKeyID(), signingKey.size(), additionalKeys.size());
        return new JwtKeyMaterial(signingKey, additionalKeys, false);
    }

    private static List<RSAKey> loadAdditionalKeys(List<TenantProperties.PublicKeyFile> files) {
        List<RSAKey> keys = new ArrayList<>();
        for (TenantProperties.PublicKeyFile file : files) {
            if (isBlank(file.path())) {
                throw new IllegalStateException("meterly.tenant.jwt.additional-public-keys[].path must be set");
            }
            RSAPublicKey publicKey = PemKeys.readPublicKey(Path.of(file.path()));
            keys.add(withKeyId(new RSAKey.Builder(publicKey), file.keyId()));
        }
        return keys;
    }

    private static RSAKey generateEphemeralKey(String keyId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(EPHEMERAL_KEY_BITS);
            KeyPair pair = generator.generateKeyPair();
            RSAKey.Builder builder = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey(pair.getPrivate());
            return withKeyId(builder, keyId);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is required by every Java platform", e);
        }
    }

    /** Sets {@code alg}/{@code use} and the configured kid, or the RFC 7638 thumbprint when none is given. */
    private static RSAKey withKeyId(RSAKey.Builder builder, String keyId) {
        builder.algorithm(JWSAlgorithm.RS256).keyUse(KeyUse.SIGNATURE);
        try {
            if (isBlank(keyId)) {
                builder.keyIDFromThumbprint();
            } else {
                builder.keyID(keyId.strip());
            }
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot compute the JWK thumbprint", e);
        }
        return builder.build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
