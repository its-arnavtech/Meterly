package com.meterly.tenant.jwt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal PEM reader for RSA keys using only the JDK: PKCS#8 private keys ({@code BEGIN PRIVATE KEY}) and
 * X.509 SubjectPublicKeyInfo public keys ({@code BEGIN PUBLIC KEY}), the formats written by
 * {@code scripts/bootstrap.ps1} and by {@code openssl genpkey}.
 */
final class PemKeys {

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");
    private static final String PRIVATE_KEY_LABEL = "PRIVATE KEY";
    private static final String PUBLIC_KEY_LABEL = "PUBLIC KEY";

    private PemKeys() {
    }

    static RSAPrivateCrtKey readPrivateKey(Path path) {
        byte[] der = readBlock(path, PRIVATE_KEY_LABEL);
        try {
            if (KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der))
                    instanceof RSAPrivateCrtKey key) {
                return key;
            }
            throw new IllegalStateException("JWT private key " + path + " lacks the CRT parameters of an RSA key");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JWT private key " + path + " is not a valid PKCS#8 RSA key", e);
        }
    }

    static RSAPublicKey readPublicKey(Path path) {
        byte[] der = readBlock(path, PUBLIC_KEY_LABEL);
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IllegalStateException("JWT public key " + path + " is not a valid RSA SubjectPublicKeyInfo key", e);
        }
    }

    /** Derives the public half from the modulus and public exponent carried by a CRT private key. */
    static RSAPublicKey publicKeyOf(RSAPrivateCrtKey privateKey) {
        try {
            RSAPublicKeySpec spec = new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent());
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot derive the RSA public key", e);
        }
    }

    private static byte[] readBlock(Path path, String expectedLabel) {
        String pem;
        try {
            pem = Files.readString(path, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read JWT key file " + path, e);
        }
        Matcher matcher = PEM_BLOCK.matcher(pem);
        if (!matcher.find()) {
            throw new IllegalStateException("JWT key file " + path + " does not contain a PEM block");
        }
        String label = matcher.group(1);
        if ("RSA PRIVATE KEY".equals(label)) {
            throw new IllegalStateException("JWT key file " + path + " is PKCS#1; convert it with "
                    + "'openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem'");
        }
        if (!expectedLabel.equals(label)) {
            throw new IllegalStateException(
                    "JWT key file " + path + " contains '" + label + "' but '" + expectedLabel + "' is required");
        }
        return Base64.getMimeDecoder().decode(matcher.group(2));
    }
}
