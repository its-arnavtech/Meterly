package com.meterly.metering.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.meterly.common.event.Topics;
import com.meterly.common.event.UsageEventMessage;
import com.meterly.metering.MeteringIntegrationTest;
import com.meterly.metering.MeteringMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.SendResult;

/**
 * POC section 7 "Idempotent metering" as an automated test: 1,000 unique events with 5% duplicate
 * idempotency keys (client retries) and 5% redelivered identical records must produce exactly as many
 * ledger rows as there are unique keys, and a counter equal to the sum of the unique quantities.
 * Re-consuming the whole topic from the beginning afterwards must not change a single number.
 */
class UsageVolumeIT extends MeteringIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(UsageVolumeIT.class);
    private static final int UNIQUE_EVENTS = 1_000;
    private static final int DUPLICATE_KEYS = 50;
    private static final int REDELIVERIES = 50;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Test
    void duplicatesAndRedeliveriesNeverChangeTheCountedTotal() throws Exception {
        TenantFixture tenant = givenTenant("Volume Inc");
        Random random = new Random(20260922L);

        List<UsageEventMessage> unique = new ArrayList<>(UNIQUE_EVENTS);
        long expectedUnits = 0;
        for (int i = 0; i < UNIQUE_EVENTS; i++) {
            UsageEventMessage event = usageEvent(tenant, "poc-" + i + "-" + UUID.randomUUID(), 1 + random.nextInt(5));
            unique.add(event);
            expectedUnits += event.quantity();
        }
        List<UsageEventMessage> traffic = new ArrayList<>(unique);
        for (int i = 0; i < DUPLICATE_KEYS; i++) {
            // Client retry after a timeout: same key and quantity, new envelope.
            UsageEventMessage original = unique.get(random.nextInt(UNIQUE_EVENTS));
            traffic.add(new UsageEventMessage(original.idempotencyKey(), original.tenantId(), original.resourceId(),
                    original.quantity(), original.occurredAt(), clock.instant(), "retry-" + original.idempotencyKey()));
        }
        for (int i = 0; i < REDELIVERIES; i++) {
            // Kafka redelivery: byte-for-byte identical record produced again.
            traffic.add(unique.get(random.nextInt(UNIQUE_EVENTS)));
        }

        double processedBefore = processedEvents();
        publishAll(traffic);
        awaitProcessed(processedBefore + traffic.size());

        assertThat(ledgerCount(tenant.tenantId())).isEqualTo(UNIQUE_EVENTS);
        assertThat(ledgerUnits(tenant.tenantId(), tenant.resourceId())).isEqualTo(expectedUnits);
        assertThat(projectedUnits(tenant.tenantId(), tenant.resourceId())).isEqualTo(expectedUnits);
        assertThat(cachedUnits(tenant.tenantId(), tenant.resourceId())).isEqualTo(expectedUnits);

        replayTopicFromTheBeginning();

        assertThat(ledgerCount(tenant.tenantId())).isEqualTo(UNIQUE_EVENTS);
        assertThat(ledgerUnits(tenant.tenantId(), tenant.resourceId())).isEqualTo(expectedUnits);
        assertThat(projectedUnits(tenant.tenantId(), tenant.resourceId())).isEqualTo(expectedUnits);
        assertThat(cachedUnits(tenant.tenantId(), tenant.resourceId())).isEqualTo(expectedUnits);
        log.info("POC volume check: {} unique events, {} retries, {} redeliveries, {} units counted once",
                UNIQUE_EVENTS, DUPLICATE_KEYS, REDELIVERIES, expectedUnits);
    }

    private void publishAll(List<UsageEventMessage> events) throws Exception {
        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(events.size());
        for (UsageEventMessage event : events) {
            sends.add(kafkaTemplate.send(Topics.USAGE_EVENTS, event.tenantId().toString(), json(event)));
        }
        kafkaTemplate.flush();
        for (CompletableFuture<SendResult<String, String>> send : sends) {
            send.get(60, TimeUnit.SECONDS);
        }
    }

    /** Stops the listener, rewinds the consumer group to offset 0 and lets it consume everything again. */
    private void replayTopicFromTheBeginning() throws Exception {
        var container = listenerRegistry.getListenerContainer(UsageEventConsumer.LISTENER_ID);
        container.stop();
        try (AdminClient admin = admin()) {
            String group = "metering-service";
            await().atMost(Duration.ofSeconds(60)).until(() -> groupState(admin, group) == ConsumerGroupState.EMPTY);
            TopicDescription topic = admin.describeTopics(List.of(Topics.USAGE_EVENTS)).allTopicNames().get()
                    .get(Topics.USAGE_EVENTS);
            Map<TopicPartition, OffsetAndMetadata> rewound = new HashMap<>();
            topic.partitions().forEach(partition ->
                    rewound.put(new TopicPartition(Topics.USAGE_EVENTS, partition.partition()), new OffsetAndMetadata(0)));
            admin.alterConsumerGroupOffsets(group, rewound).all().get(60, TimeUnit.SECONDS);

            container.start();
            await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> assertThat(lagOf(admin, group)).isZero());
        }
    }

    private static ConsumerGroupState groupState(AdminClient admin, String group) throws Exception {
        ConsumerGroupDescription description = admin.describeConsumerGroups(List.of(group))
                .describedGroups().get(group).get(30, TimeUnit.SECONDS);
        return description.state();
    }

    /** Total lag of the consumer group on {@code usage-events}: 0 once the replay is fully processed. */
    private static long lagOf(AdminClient admin, String group) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata().get(30, TimeUnit.SECONDS);
        Map<TopicPartition, OffsetSpec> ends = new HashMap<>();
        committed.keySet().stream()
                .filter(partition -> partition.topic().equals(Topics.USAGE_EVENTS))
                .forEach(partition -> ends.put(partition, OffsetSpec.latest()));
        long lag = 0;
        for (var end : admin.listOffsets(ends).all().get(30, TimeUnit.SECONDS).entrySet()) {
            lag += end.getValue().offset() - committed.get(end.getKey()).offset();
        }
        return lag;
    }

    private void awaitProcessed(double expected) {
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(processedEvents()).isGreaterThanOrEqualTo(expected));
    }

    private double processedEvents() {
        return counterValue(MeteringMetrics.EVENTS, "result", "inserted")
                + counterValue(MeteringMetrics.EVENTS, "result", "duplicate");
    }
}
