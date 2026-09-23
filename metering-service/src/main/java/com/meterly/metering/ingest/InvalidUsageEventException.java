package com.meterly.metering.ingest;

/**
 * A usage event record that can never be processed (malformed JSON, missing or invalid fields, a key
 * that does not match the tenant). It is an {@link IllegalArgumentException}, which the Kafka error
 * handler classifies as non-retryable: the record goes straight to {@code usage-events.DLT}.
 */
public class InvalidUsageEventException extends IllegalArgumentException {

    public InvalidUsageEventException(String message) {
        super(message);
    }

    public InvalidUsageEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
