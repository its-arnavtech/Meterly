package com.meterly.tenant.apikey;

import com.meterly.common.crypto.Hashing;
import java.util.regex.Pattern;

/**
 * API-key format: {@code mk_live_} followed by 43 URL-safe base64 characters (256 random bits). Only the
 * SHA-256 hex digest is stored; the first 12 characters are kept as a non-secret display prefix. A plain
 * digest (no salt, no slow KDF) is appropriate because the key itself carries 256 bits of entropy.
 */
public final class ApiKeys {

    public static final String LIVE_PREFIX = "mk_live_";
    public static final int DISPLAY_PREFIX_LENGTH = 12;
    private static final int RANDOM_BYTES = 32;
    /** Anything that could be a Meterly key (including the demo seed's {@code mk_demo_...} keys). */
    private static final Pattern PLAUSIBLE_KEY = Pattern.compile("mk_[A-Za-z0-9_-]{9,200}");

    private ApiKeys() {
    }

    /** A freshly generated key; {@code rawKey} must be shown to the user once and never stored or logged. */
    public record GeneratedKey(String rawKey, String displayPrefix, String hash) {

        @Override
        public String toString() {
            return "GeneratedKey[displayPrefix=" + displayPrefix + ", rawKey=<redacted>]";
        }
    }

    public static GeneratedKey generate() {
        String rawKey = LIVE_PREFIX + Hashing.randomToken(RANDOM_BYTES);
        return new GeneratedKey(rawKey, rawKey.substring(0, DISPLAY_PREFIX_LENGTH), hash(rawKey));
    }

    public static String hash(String rawKey) {
        return Hashing.sha256Hex(rawKey);
    }

    /** Cheap shape check before hashing and querying, so junk input never reaches the database. */
    public static boolean isPlausible(String rawKey) {
        return rawKey != null && PLAUSIBLE_KEY.matcher(rawKey).matches();
    }
}
