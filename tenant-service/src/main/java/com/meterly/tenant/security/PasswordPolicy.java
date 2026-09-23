package com.meterly.tenant.security;

import java.nio.charset.StandardCharsets;

/**
 * Password length rules, measured in UTF-8 bytes because BCrypt only reads the first 72 bytes: a longer
 * password would silently be truncated (and Spring Security rejects it outright), so it is refused up front.
 */
public final class PasswordPolicy {

    public static final int MIN_BYTES = 10;
    public static final int MAX_BYTES = 72;
    public static final String MESSAGE = "must be between " + MIN_BYTES + " and " + MAX_BYTES + " bytes (UTF-8)";

    private PasswordPolicy() {
    }

    public static int utf8Length(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * True when the password satisfies the policy. Used for new passwords (signup, user creation) and at
     * login, where a password outside the policy cannot match any stored hash and is rejected as invalid
     * credentials without being handed to BCrypt.
     */
    public static boolean isAcceptable(String password) {
        if (password == null) {
            return false;
        }
        int bytes = utf8Length(password);
        return bytes >= MIN_BYTES && bytes <= MAX_BYTES;
    }
}
