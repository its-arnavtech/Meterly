package com.meterly.tenant.support;

import java.util.Locale;

/** Input normalization applied in request records' compact constructors, before Bean Validation runs. */
public final class Text {

    private Text() {
    }

    /** Strips surrounding whitespace; {@code null} stays {@code null}. */
    public static String strip(String value) {
        return value == null ? null : value.strip();
    }

    /** Emails are unique case-insensitively, so they are stored and looked up in lower case. */
    public static String normalizeEmail(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }
}
