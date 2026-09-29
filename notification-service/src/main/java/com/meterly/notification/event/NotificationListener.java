package com.meterly.notification.event;

import com.meterly.common.event.Topics;
import com.meterly.notification.delivery.NotificationProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationListener {
    private final NotificationEventParser parser;
    private final NotificationProcessor processor;
    public NotificationListener(NotificationEventParser parser, NotificationProcessor processor) {
        this.parser = parser;
        this.processor = processor;
    }
    @KafkaListener(topics = Topics.NOTIFICATION_EVENTS, groupId = "notification-service")
    public void accept(ConsumerRecord<String, String> record) {
        var event = parser.parse(record.value());
        if (!event.tenantId().toString().equals(record.key())) {
            throw new InvalidNotificationEventException("Record key does not match event tenant");
        }
        processor.process(event);
    }
}
