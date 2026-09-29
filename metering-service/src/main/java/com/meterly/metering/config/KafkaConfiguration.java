package com.meterly.metering.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.meterly.common.event.Topics;
import com.meterly.common.kafka.CorrelationRecordInterceptor;
import com.meterly.metering.MeteringMetrics;
import com.meterly.metering.ingest.InvalidUsageEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Kafka wiring: topics, the listener container factory for {@code usage-events} and its error handling.
 *
 * <p>Error policy: records that can never succeed (unparseable or invalid events, integrity violations
 * such as a resource that belongs to another tenant, rejected by the composite foreign key) go to
 * {@code usage-events.DLT} immediately. Anything else (database or broker hiccups) is retried with
 * bounded exponential backoff, then dead-lettered. The offset is committed only after the record was
 * processed or dead-lettered, so nothing is lost.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfiguration.class);

    @Bean
    NewTopic usageEventsTopic(MeteringProperties properties) {
        return topic(Topics.USAGE_EVENTS, properties.topics().usageEventsPartitions(), properties);
    }

    @Bean
    NewTopic usageEventsDeadLetterTopic(MeteringProperties properties) {
        return topic(Topics.USAGE_EVENTS_DLT, properties.topics().usageEventsPartitions(), properties);
    }

    @Bean
    NewTopic notificationEventsTopic(MeteringProperties properties) {
        return topic(Topics.NOTIFICATION_EVENTS, properties.topics().notificationEventsPartitions(), properties);
    }

    @Bean
    NewTopic notificationEventsDeadLetterTopic(MeteringProperties properties) {
        return topic(Topics.NOTIFICATION_EVENTS_DLT, properties.topics().notificationEventsPartitions(), properties);
    }

    /**
     * Boot's configurer is applied first so {@code spring.kafka.listener.*} (auto-startup, ...) keeps
     * working; then the metering specifics: concurrency, RECORD acks, correlation ids, error handler.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler usageEventErrorHandler,
            MeteringProperties properties) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setConcurrency(properties.consumer().concurrency());
        factory.getContainerProperties().setAckMode(AckMode.RECORD);
        factory.setRecordInterceptor(new CorrelationRecordInterceptor<>());
        factory.setCommonErrorHandler(usageEventErrorHandler);
        return factory;
    }

    @Bean
    DefaultErrorHandler usageEventErrorHandler(KafkaTemplate<String, String> kafkaTemplate, MeteringMetrics metrics,
                                               MeteringProperties properties) {
        // Partition -1 lets the producer pick the DLT partition by key, so the DLT may have fewer partitions.
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(Topics.USAGE_EVENTS_DLT, -1));
        deadLetters.setFailIfSendResultIsError(true);
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            String reason = deadLetterReason(exception);
            deadLetters.accept(record, exception);
            metrics.deadLettered(reason);
            logDeadLetter(record, reason, exception);
        };

        MeteringProperties.Backoff settings = properties.consumer().backoff();
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(settings.maxRetries());
        backOff.setInitialInterval(settings.initialInterval().toMillis());
        backOff.setMultiplier(settings.multiplier());
        backOff.setMaxInterval(settings.maxInterval().toMillis());

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                InvalidUsageEventException.class,
                JsonProcessingException.class,
                IllegalArgumentException.class,
                DataIntegrityViolationException.class);
        return handler;
    }

    /** Low-cardinality reason tag: {@code invalid}, {@code integrity} or {@code retries_exhausted}. */
    static String deadLetterReason(Exception exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataIntegrityViolationException) {
                return "integrity";
            }
            if (cause instanceof IllegalArgumentException || cause instanceof JsonProcessingException) {
                return "invalid";
            }
        }
        return "retries_exhausted";
    }

    private static void logDeadLetter(ConsumerRecord<?, ?> record, String reason, Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        // Record values and SQL messages are not logged; topic/partition/offset locate the record.
        log.warn("Usage event {}-{}@{} sent to {} (reason={}, cause={})", record.topic(), record.partition(),
                record.offset(), Topics.USAGE_EVENTS_DLT, reason, root.getClass().getSimpleName());
    }

    private static NewTopic topic(String name, int partitions, MeteringProperties properties) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(properties.topics().replicationFactor())
                .build();
    }
}
