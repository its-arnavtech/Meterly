package com.meterly.common.event;

/** Kafka topic names. All records are keyed by {@code tenant_id} to preserve per-tenant ordering. */
public final class Topics {

    /** Raw usage events published by ingestion-service, consumed by metering-service. */
    public static final String USAGE_EVENTS = "usage-events";
    /** Dead-letter topic for usage events that can never be processed (malformed, unknown resource). */
    public static final String USAGE_EVENTS_DLT = "usage-events.DLT";
    /** Invoice-issued and usage-threshold events, consumed by notification-service. */
    public static final String NOTIFICATION_EVENTS = "notification-events";
    public static final String NOTIFICATION_EVENTS_DLT = "notification-events.DLT";

    private Topics() {
    }
}
