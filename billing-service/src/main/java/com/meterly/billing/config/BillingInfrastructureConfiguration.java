package com.meterly.billing.config;

import com.meterly.billing.domain.PricingEngine;
import com.meterly.common.event.Topics;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Pricing engine bean, cluster-wide job locking (ShedLock on the {@code shedlock} table, using database
 * time so replica clock skew cannot break mutual exclusion) and the topics billing publishes to.
 */
@Configuration(proxyBeanMethods = false)
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class BillingInfrastructureConfiguration {

    /** Partition count fixed by the contract (section 6); replication is left to the broker default. */
    static final int NOTIFICATION_PARTITIONS = 6;

    @Bean
    PricingEngine pricingEngine() {
        return new PricingEngine();
    }

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }

    @Bean
    NewTopic notificationEventsTopic() {
        return TopicBuilder.name(Topics.NOTIFICATION_EVENTS).partitions(NOTIFICATION_PARTITIONS).build();
    }

    /** Same partition count: notification-service dead-letters each record to its original partition. */
    @Bean
    NewTopic notificationEventsDeadLetterTopic() {
        return TopicBuilder.name(Topics.NOTIFICATION_EVENTS_DLT).partitions(NOTIFICATION_PARTITIONS).build();
    }

    /**
     * Scheduled jobs (period close, outbox relay) run unless {@code meterly.billing.scheduling-enabled=false}.
     * The job beans exist either way, so tests and the internal endpoint can invoke them directly.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "meterly.billing.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfiguration {
    }
}
