package com.meterly.notification.delivery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters (Prometheus names in brackets):
 * <ul>
 *   <li>{@code meterly.notification.sent{type, outcome=sent|failed}} [{@code meterly_notification_sent_total}]:
 *       one per send attempt, from the consumer and the retry job;</li>
 *   <li>{@code meterly.notification.dlt{reason=invalid_event|retries_exhausted}} [{@code meterly_notification_dlt_total}]:
 *       records published to {@code notification-events.DLT}.</li>
 * </ul>
 */
@Component
public class NotificationMetrics {

    public static final String SENT = "meterly.notification.sent";
    public static final String DLT = "meterly.notification.dlt";

    private final MeterRegistry registry;

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void sendAttempt(String type, DeliveryOutcome outcome) {
        Counter.builder(SENT)
                .description("Notification send attempts by event type and outcome")
                .tag("type", type)
                .tag("outcome", outcome.tag())
                .register(registry)
                .increment();
    }

    public void deadLettered(String reason) {
        Counter.builder(DLT)
                .description("Notification events published to the dead-letter topic")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
