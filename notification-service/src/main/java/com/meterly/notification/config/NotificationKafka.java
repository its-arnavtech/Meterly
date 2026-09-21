package com.meterly.notification.config;

import com.meterly.common.event.Topics;
import com.meterly.common.kafka.CorrelationRecordInterceptor;
import com.meterly.notification.event.InvalidNotificationEventException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class NotificationKafka {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> producer,
            NotificationProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setRecordInterceptor(new CorrelationRecordInterceptor<>());
        var recoverer = new DeadLetterPublishingRecoverer(producer,
                (record, error) -> new TopicPartition(Topics.NOTIFICATION_EVENTS + ".DLT", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        var settings = properties.consumer();
        var backoff = new ExponentialBackOffWithMaxRetries(settings.maxRetries());
        backoff.setInitialInterval(settings.initialBackoff().toMillis());
        backoff.setMultiplier(settings.multiplier());
        backoff.setMaxInterval(settings.maxBackoff().toMillis());
        var errors = new DefaultErrorHandler(recoverer, backoff);
        errors.addNotRetryableExceptions(InvalidNotificationEventException.class);
        factory.setCommonErrorHandler(errors);
        return factory;
    }
}
