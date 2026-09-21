package com.meterly.tenant;

import com.meterly.common.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class Secrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Secrets() {}
    static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    static void validatePassword(String value) {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length < 10 || length > 72) throw ApiException.badRequest("VALIDATION_FAILED",
                "Password must be between 10 and 72 UTF-8 bytes");
    }
    static String email(String value) { return value.trim().toLowerCase(Locale.ROOT); }
}
