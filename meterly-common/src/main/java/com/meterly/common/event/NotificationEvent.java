package com.meterly.common.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Events on the {@value Topics#NOTIFICATION_EVENTS} topic. The hierarchy is sealed, so a consumer that
 * handles them with a pattern-matching {@code switch} gets a compile error, not a silent drop, when a
 * new event type is added.
 *
 * <p>Wire format is JSON with a {@code type} discriminator ({@code invoice.issued},
 * {@code usage.threshold_crossed}). {@code event_id} is the consumer's idempotency key.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = NotificationEvent.InvoiceIssued.class, name = NotificationEvent.INVOICE_ISSUED),
        @JsonSubTypes.Type(value = NotificationEvent.UsageThresholdCrossed.class,
                name = NotificationEvent.USAGE_THRESHOLD_CROSSED)
})
public sealed interface NotificationEvent {

    String INVOICE_ISSUED = "invoice.issued";
    String USAGE_THRESHOLD_CROSSED = "usage.threshold_crossed";

    UUID eventId();

    UUID tenantId();

    Instant occurredAt();

    /** Emitted by billing-service (via its transactional outbox) when an invoice is generated. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record InvoiceIssued(
            UUID eventId,
            UUID tenantId,
            Instant occurredAt,
            UUID invoiceId,
            String invoiceNumber,
            LocalDate periodStart,
            LocalDate periodEnd,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
            String currency) implements NotificationEvent {
    }

    /** Emitted by metering-service when a resource's open-period usage crosses a % of included units. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record UsageThresholdCrossed(
            UUID eventId,
            UUID tenantId,
            Instant occurredAt,
            UUID resourceId,
            String resourceName,
            LocalDate periodStart,
            int thresholdPercent,
            long totalUnits,
            long includedUnits) implements NotificationEvent {
    }
}
