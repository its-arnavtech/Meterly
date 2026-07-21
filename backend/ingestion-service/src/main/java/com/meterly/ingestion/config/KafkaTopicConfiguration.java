package com.meterly.ingestion.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfiguration {
    @Bean
    NewTopic usageEventsTopic() {
        return TopicBuilder.name("usage-events").partitions(3).replicas(1).build();
    }
}

