package com.meterly.metering.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of metering-service under {@code meterly.metering.*}. Every value has a safe local default and
 * can be overridden by environment variable (see {@code application.yml} for the variable names).
 *
 * <pre>
 * meterly.metering.consumer.group-id                     Kafka consumer group (default metering-service)
 * meterly.metering.consumer.concurrency                  listener threads per instance (default 3)
 * meterly.metering.consumer.backoff.initial-interval     first retry delay for transient failures (500ms)
 * meterly.metering.consumer.backoff.multiplier           exponential factor (2.0)
 * meterly.metering.consumer.backoff.max-interval         cap per retry delay (10s)
 * meterly.metering.consumer.backoff.max-retries          retries before the record goes to the DLT (6)
 * meterly.metering.topics.usage-events-partitions        partitions of usage-events and its DLT (12)
 * meterly.metering.topics.notification-events-partitions partitions of notification-events and its DLT (6)
 * meterly.metering.topics.replication-factor             replication factor for created topics (1)
 * meterly.metering.outbox.relay-interval                 delay between outbox relay runs (2s)
 * meterly.metering.outbox.batch-size                     rows claimed per relay run (100)
 * meterly.metering.outbox.send-timeout                   max wait for the broker ack of a batch (10s)
 * meterly.metering.stomp.heartbeat                       server STOMP heartbeat, both directions (10s)
 * meterly.metering.reconciliation-cron                   nightly reconciliation, UTC (0 0 3 * * *)
 * </pre>
 */
@ConfigurationProperties("meterly.metering")
public record MeteringProperties(
        @DefaultValue Consumer consumer,
        @DefaultValue Topics topics,
        @DefaultValue Outbox outbox,
        @DefaultValue Stomp stomp,
        @DefaultValue("0 0 3 * * *") String reconciliationCron) {

    public MeteringProperties {
        if (reconciliationCron == null || reconciliationCron.isBlank()) {
            throw new IllegalArgumentException("meterly.metering.reconciliation-cron must not be blank");
        }
    }

    public record Consumer(
            @DefaultValue("metering-service") String groupId,
            @DefaultValue("3") int concurrency,
            @DefaultValue Backoff backoff) {

        public Consumer {
            if (concurrency < 1) {
                throw new IllegalArgumentException("meterly.metering.consumer.concurrency must be >= 1");
            }
        }
    }

    public record Backoff(
            @DefaultValue("500ms") Duration initialInterval,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("10s") Duration maxInterval,
            @DefaultValue("6") int maxRetries) {

        public Backoff {
            if (multiplier < 1.0) {
                throw new IllegalArgumentException("meterly.metering.consumer.backoff.multiplier must be >= 1");
            }
            if (maxRetries < 0) {
                throw new IllegalArgumentException("meterly.metering.consumer.backoff.max-retries must be >= 0");
            }
        }
    }

    public record Topics(
            @DefaultValue("12") int usageEventsPartitions,
            @DefaultValue("6") int notificationEventsPartitions,
            @DefaultValue("1") short replicationFactor) {
    }

    public record Outbox(
            @DefaultValue("2s") Duration relayInterval,
            @DefaultValue("100") int batchSize,
            @DefaultValue("10s") Duration sendTimeout) {

        public Outbox {
            if (batchSize < 1) {
                throw new IllegalArgumentException("meterly.metering.outbox.batch-size must be >= 1");
            }
        }
    }

    public record Stomp(@DefaultValue("10s") Duration heartbeat) {
    }
}
