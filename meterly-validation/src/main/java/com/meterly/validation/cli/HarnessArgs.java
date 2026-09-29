package com.meterly.validation.cli;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses {@code --flag value} pairs plus an optional leading sub-command ({@code validate}, the
 * default, or {@code load}). Never reads a secret from here: passwords come from environment
 * variables only (see {@code ValidationMain}), so they never appear in shell history or process
 * listings.
 */
public final class HarnessArgs {

    private final String command;
    private final Map<String, String> options;

    private HarnessArgs(String command, Map<String, String> options) {
        this.command = command;
        this.options = options;
    }

    public static HarnessArgs parse(String[] args) {
        String command = "validate";
        int start = 0;
        if (args.length > 0 && !args[0].startsWith("--")) {
            command = args[0];
            start = 1;
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = start; i < args.length; i++) {
            String token = args[i];
            if (!token.startsWith("--")) {
                continue;
            }
            String key = token.substring(2);
            boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
            options.put(key, hasValue ? args[++i] : "true");
        }
        return new HarnessArgs(command, options);
    }

    public String command() {
        return command;
    }

    public String get(String key, String defaultValue) {
        return options.getOrDefault(key, defaultValue);
    }

    public String require(String key) {
        String value = options.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required --" + key);
        }
        return value;
    }

    public double getDouble(String key, double defaultValue) {
        return options.containsKey(key) ? Double.parseDouble(options.get(key)) : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        return options.containsKey(key) ? Integer.parseInt(options.get(key)) : defaultValue;
    }
}
