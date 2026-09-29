package com.meterly.notification.delivery;

/** Result of one send attempt through the {@code EmailSender}. */
public sealed interface DeliveryOutcome {

    /** Metric tag value for {@code meterly.notification.sent{outcome}}. */
    default String tag() {
        return switch (this) {
            case Sent sent -> "sent";
            case Failed failed -> "failed";
        };
    }

    record Sent() implements DeliveryOutcome {
    }

    /** @param error log-safe, truncated description stored in {@code notification.error} */
    record Failed(String error) implements DeliveryOutcome {
    }
}
