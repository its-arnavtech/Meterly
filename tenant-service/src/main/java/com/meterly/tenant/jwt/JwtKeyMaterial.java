package com.meterly.tenant.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The RSA key that signs access tokens plus any extra public keys that must still verify (rotation).
 *
 * @param signingKey        private + public key with {@code kid}, {@code alg=RS256}, {@code use=sig}
 * @param additionalKeys    public-only keys published in the JWKS and accepted by the local decoder
 * @param ephemeral         whether the signing key was generated at startup (not shared, lost on restart)
 */
public record JwtKeyMaterial(RSAKey signingKey, List<RSAKey> additionalKeys, boolean ephemeral) {

    public static final int MINIMUM_KEY_BITS = 2048;

    public JwtKeyMaterial {
        Objects.requireNonNull(signingKey, "signingKey");
        if (!signingKey.isPrivate()) {
            throw new IllegalArgumentException("The JWT signing key must include the private key");
        }
        additionalKeys = additionalKeys.stream().map(RSAKey::toPublicJWK).toList();
        Set<String> keyIds = new HashSet<>();
        keyIds.add(signingKey.getKeyID());
        for (RSAKey key : additionalKeys) {
            if (!keyIds.add(key.getKeyID())) {
                throw new IllegalArgumentException("Duplicate JWT key id '" + key.getKeyID() + "'");
            }
        }
        for (RSAKey key : allPublicKeys(signingKey, additionalKeys)) {
            if (key.size() < MINIMUM_KEY_BITS) {
                throw new IllegalArgumentException("JWT key '" + key.getKeyID() + "' is shorter than "
                        + MINIMUM_KEY_BITS + " bits");
            }
        }
    }

    public String signingKeyId() {
        return signingKey.getKeyID();
    }

    /** Every key a valid Meterly token may be signed with: the current signing key first. */
    public List<RSAKey> verificationKeys() {
        return allPublicKeys(signingKey, additionalKeys);
    }

    public JWKSet publicJwkSet() {
        return new JWKSet(List.<JWK>copyOf(verificationKeys()));
    }

    /** The JWKS document: public parameters only ({@code kty, n, e, kid, alg, use}). */
    public Map<String, Object> jwksDocument() {
        return publicJwkSet().toJSONObject(true);
    }

    public RSAPublicKey signingPublicKey() {
        try {
            return signingKey.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException("Invalid RSA signing key", e);
        }
    }

    private static List<RSAKey> allPublicKeys(RSAKey signingKey, List<RSAKey> additionalKeys) {
        List<RSAKey> keys = new ArrayList<>();
        keys.add(signingKey.toPublicJWK());
        keys.addAll(additionalKeys);
        return List.copyOf(keys);
    }
}
