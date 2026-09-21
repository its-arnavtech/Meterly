package com.meterly.common.web;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Request/correlation ID conventions. The same ID travels as the {@value #HTTP_HEADER} HTTP header,
 * the {@value #KAFKA_HEADER} Kafka record header and the {@value #MDC_KEY} MDC key (so it appears in
 * every structured JSON log line).
 */
public final class CorrelationId {

    public static final String HTTP_HEADER = "X-Request-Id";
    public static final String KAFKA_HEADER = "x-request-id";
    public static final String MDC_KEY = "request_id";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._\\-]{1,128}");

    private CorrelationId() {
    }

    /** Accepts a caller-supplied ID only if it is short and log-safe; otherwise generates a new one. */
    public static String sanitizeOrGenerate(String candidate) {
        if (candidate != null && SAFE.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }
}
