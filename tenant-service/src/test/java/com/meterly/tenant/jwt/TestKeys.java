package com.meterly.tenant.jwt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Generates RSA key pairs and writes them as PEM files, the way {@code scripts/bootstrap.ps1} does. */
final class TestKeys {

    private TestKeys() {
    }

    static KeyPair generate(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Path writePrivateKey(Path directory, String fileName, KeyPair pair) throws IOException {
        return write(directory, fileName, "PRIVATE KEY", pair.getPrivate().getEncoded());
    }

    static Path writePublicKey(Path directory, String fileName, KeyPair pair) throws IOException {
        return write(directory, fileName, "PUBLIC KEY", pair.getPublic().getEncoded());
    }

    static Path write(Path directory, String fileName, String label, byte[] der) throws IOException {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        String pem = "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
        Path file = directory.resolve(fileName);
        Files.writeString(file, pem, StandardCharsets.US_ASCII);
        return file;
    }
}
