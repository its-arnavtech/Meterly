package com.meterly.validation.report;

import java.util.LinkedHashMap;
import java.util.Map;

/** The outcome of one contract §9 check, plus any measurements worth recording in the report. */
public record CheckResult(String name, boolean passed, String message, Map<String, String> metrics) {

    public static CheckResult pass(String name, String message) {
        return new CheckResult(name, true, message, Map.of());
    }

    public static CheckResult pass(String name, String message, Map<String, String> metrics) {
        return new CheckResult(name, true, message, Map.copyOf(metrics));
    }

    public static CheckResult fail(String name, String message) {
        return new CheckResult(name, false, message, Map.of());
    }

    public static CheckResult fail(String name, String message, Map<String, String> metrics) {
        return new CheckResult(name, false, message, Map.copyOf(metrics));
    }

    /** Builder-ish helper for checks that accumulate several measurements before deciding pass/fail. */
    public static final class Metrics {
        private final Map<String, String> values = new LinkedHashMap<>();

        public Metrics put(String key, Object value) {
            values.put(key, String.valueOf(value));
            return this;
        }

        public Map<String, String> build() {
            return Map.copyOf(values);
        }
    }
}
