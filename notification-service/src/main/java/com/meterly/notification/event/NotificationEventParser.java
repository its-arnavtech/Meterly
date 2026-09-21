package com.meterly.notification.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.event.NotificationEvent;
import com.meterly.common.event.NotificationEvent.InvoiceIssued;
import com.meterly.common.event.NotificationEvent.UsageThresholdCrossed;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns a {@code notification-events} record value into a {@link NotificationEvent} using the Spring
 * {@link ObjectMapper} (the {@code type} property selects the subtype) and checks the fields the
 * templates and the idempotency key depend on. Every failure is an
 * {@link InvalidNotificationEventException}, i.e. dead-lettered rather than retried.
 */
@Component
public class NotificationEventParser {

    private final ObjectMapper objectMapper;

    public NotificationEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NotificationEvent parse(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidNotificationEventException("Record value is empty");
        }
        NotificationEvent event;
        try {
            event = objectMapper.readValue(json, NotificationEvent.class);
        } catch (JsonProcessingException e) {
            throw new InvalidNotificationEventException("Unreadable notification event: " + e.getOriginalMessage(), e);
        }
        if (event == null) {
            throw new InvalidNotificationEventException("Record value is JSON null");
        }
        List<String> missing = missingFields(event);
        if (!missing.isEmpty()) {
            throw new InvalidNotificationEventException("Notification event " + event.eventId()
                    + " is missing or has invalid fields: " + String.join(", ", missing));
        }
        return event;
    }

    private static List<String> missingFields(NotificationEvent event) {
        List<String> missing = new ArrayList<>();
        require(missing, event.eventId(), "event_id");
        require(missing, event.tenantId(), "tenant_id");
        switch (event) {
            case InvoiceIssued invoice -> {
                requireText(missing, invoice.invoiceNumber(), "invoice_number");
                require(missing, invoice.periodStart(), "period_start");
                require(missing, invoice.periodEnd(), "period_end");
                require(missing, invoice.totalAmount(), "total_amount");
                requireText(missing, invoice.currency(), "currency");
                if (invoice.periodStart() != null && invoice.periodEnd() != null
                        && !invoice.periodEnd().isAfter(invoice.periodStart())) {
                    missing.add("period_end (must be after period_start)");
                }
            }
            case UsageThresholdCrossed threshold -> {
                requireText(missing, threshold.resourceName(), "resource_name");
                require(missing, threshold.periodStart(), "period_start");
                if (threshold.thresholdPercent() <= 0) {
                    missing.add("threshold_percent (must be positive)");
                }
                if (threshold.totalUnits() < 0) {
                    missing.add("total_units (must not be negative)");
                }
                if (threshold.includedUnits() < 0) {
                    missing.add("included_units (must not be negative)");
                }
            }
        }
        return missing;
    }

    private static void require(List<String> missing, Object value, String field) {
        if (value == null) {
            missing.add(field);
        }
    }

    private static void requireText(List<String> missing, String value, String field) {
        if (value == null || value.isBlank()) {
            missing.add(field);
        }
    }
}
