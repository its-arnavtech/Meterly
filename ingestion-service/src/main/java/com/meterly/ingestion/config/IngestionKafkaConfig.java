package com.meterly.ingestion.config;

import com.meterly.common.event.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * ingestion-service is the producer for {@code usage-events} and therefore owns declaring both that
 * topic and its dead-letter topic (populated by metering-service's consumer error handler); Spring
 * Kafka's auto-configured {@code KafkaAdmin} creates any {@link NewTopic} bean on startup if missing.
 */
@Configuration(proxyBeanMethods = false)
public class IngestionKafkaConfig {

    private static final int PARTITIONS = 12;

    @Bean
    public NewTopic usageEventsTopic() {
        return TopicBuilder.name(Topics.USAGE_EVENTS).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic usageEventsDeadLetterTopic() {
        return TopicBuilder.name(Topics.USAGE_EVENTS_DLT).partitions(PARTITIONS).replicas(1).build();
    }
}
