package com.meterly.notification.config;

import com.meterly.common.event.Topics;
import com.meterly.common.kafka.CorrelationRecordInterceptor;
import com.meterly.notification.event.InvalidNotificationEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Listener container for {@code notification-events}: offsets are committed per record after processing,
 * transient failures retry with bounded exponential backoff, and records that can never succeed
 * (malformed JSON, unknown {@code type}, key/tenant mismatch) go straight to the dead-letter topic.
 */
@Configuration
@EnableScheduling
public class NotificationKafka {

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumers, KafkaTemplate<String, String> producer,
            NotificationProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        // Apply spring.kafka.listener.* (concurrency, auto-startup, ...) before the Meterly-specific settings.
        configurer.configure(factory, consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setRecordInterceptor(new CorrelationRecordInterceptor<>());

        // Partition -1 lets the producer choose, so the DLT may have fewer partitions than the source topic.
        var recoverer = new DeadLetterPublishingRecoverer(producer,
                (record, error) -> new TopicPartition(Topics.NOTIFICATION_EVENTS_DLT, -1));
        recoverer.setFailIfSendResultIsError(true);

        var settings = properties.consumer();
        var backoff = new ExponentialBackOffWithMaxRetries(settings.maxRetries());
        backoff.setInitialInterval(settings.initialBackoff().toMillis());
        backoff.setMultiplier(settings.multiplier());
        backoff.setMaxInterval(settings.maxBackoff().toMillis());

        var errors = new DefaultErrorHandler(recoverer, backoff);
        errors.addNotRetryableExceptions(InvalidNotificationEventException.class, DeserializationException.class);
        factory.setCommonErrorHandler(errors);
        return factory;
    }

    /** Idempotent: KafkaAdmin only creates missing topics (the compose topic job normally creates them). */
    @Bean
    NewTopic notificationEventsTopic(@Value("${meterly.notification.topic-partitions:6}") int partitions,
                                     @Value("${meterly.notification.topic-replicas:1}") short replicas) {
        return TopicBuilder.name(Topics.NOTIFICATION_EVENTS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    NewTopic notificationEventsDeadLetterTopic(@Value("${meterly.notification.topic-replicas:1}") short replicas) {
        return TopicBuilder.name(Topics.NOTIFICATION_EVENTS_DLT).partitions(1).replicas(replicas).build();
    }
}
