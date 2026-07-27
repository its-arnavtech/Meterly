package com.meterly.ingestion.config;

import org.apache.kafka.clients.admin.NewTopic;
import com.meterly.platform.usage.UsageEventMessage;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
public class KafkaTopicConfiguration {
    @Bean
    NewTopic usageEventsTopic() {
        return TopicBuilder.name("usage-events").partitions(3).replicas(1).build();
    }

    @Bean
    ApplicationRunner warmUsageEventsProducer(KafkaTemplate<String, UsageEventMessage> kafkaTemplate) {
        return arguments -> kafkaTemplate.partitionsFor("usage-events");
    }
}
