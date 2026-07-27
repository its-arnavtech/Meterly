package com.meterly.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.platform.notification.InvoiceGeneratedMessage;
import com.meterly.platform.notification.UsageThresholdCrossedMessage;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationEventConsumer {
    private final ObjectMapper objectMapper;
    private final NotificationDeliveryService deliveryService;

    public NotificationEventConsumer(ObjectMapper objectMapper, NotificationDeliveryService deliveryService) {
        this.objectMapper = objectMapper;
        this.deliveryService = deliveryService;
    }

    @KafkaListener(topics = "invoice-generated")
    public void invoiceGenerated(String payload) throws JsonProcessingException {
        InvoiceGeneratedMessage event = objectMapper.readValue(payload, InvoiceGeneratedMessage.class);
        deliveryService.deliver(
                event.eventId(),
                event.tenantId(),
                "INVOICE_GENERATED",
                "invoice_id=" + event.invoiceId() + ", total_amount=" + event.totalAmount()
        );
    }

    @KafkaListener(topics = "usage-threshold-crossed")
    public void usageThresholdCrossed(String payload) throws JsonProcessingException {
        UsageThresholdCrossedMessage event = objectMapper.readValue(payload, UsageThresholdCrossedMessage.class);
        deliveryService.deliver(
                event.eventId(),
                event.tenantId(),
                "USAGE_THRESHOLD_CROSSED",
                "resource_id=" + event.resourceId() + ", total_units=" + event.totalUnits()
                        + ", threshold=" + event.threshold()
        );
    }
}
