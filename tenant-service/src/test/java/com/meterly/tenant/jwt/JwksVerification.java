package com.meterly.tenant.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;

/** Verifies a token's signature with a key taken from the published JWKS, as any other service would. */
final class JwksVerification {

    private JwksVerification() {
    }

    static boolean verifies(String token, RSAKey publicKey) {
        try {
            return SignedJWT.parse(token).verify(new RSASSAVerifier(publicKey));
        } catch (ParseException | JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
