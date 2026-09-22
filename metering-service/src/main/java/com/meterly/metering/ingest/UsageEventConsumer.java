package com.meterly.metering.ingest;

import com.meterly.common.event.Topics;
import com.meterly.common.event.UsageEventMessage;
import com.meterly.common.tenant.TenantContextAspect;
import com.meterly.metering.MeteringMetrics;
import com.meterly.metering.MeteringMetrics.EventResult;
import com.meterly.metering.ingest.RecordingOutcome.Duplicate;
import com.meterly.metering.ingest.RecordingOutcome.Inserted;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka entry point for {@code usage-events} (consumer group {@code metering-service}). Each record is
 * parsed, recorded in its own tenant-bound transaction and acknowledged afterwards (ack mode RECORD).
 * Exceptions go to the container's error handler: invalid records and integrity violations are
 * dead-lettered immediately, transient failures are retried with exponential backoff first
 * (see {@code KafkaConfiguration}).
 */
@Component
public class UsageEventConsumer {

    public static final String LISTENER_ID = "usage-events-listener";

    private static final Logger log = LoggerFactory.getLogger(UsageEventConsumer.class);

    private final UsageEventParser parser;
    private final UsageRecorder recorder;
    private final MeteringMetrics metrics;

    public UsageEventConsumer(UsageEventParser parser, UsageRecorder recorder, MeteringMetrics metrics) {
        this.parser = parser;
        this.recorder = recorder;
        this.metrics = metrics;
    }

    @KafkaListener(id = LISTENER_ID, topics = Topics.USAGE_EVENTS,
            groupId = "${meterly.metering.consumer.group-id:metering-service}")
    public void onUsageEvent(ConsumerRecord<String, String> record) {
        long started = System.nanoTime();
        UsageEventMessage event = parser.parse(record);
        MDC.put(TenantContextAspect.MDC_TENANT_ID, event.tenantId().toString());
        try {
            RecordingOutcome outcome = recorder.record(event);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            switch (outcome) {
                case Inserted inserted -> onInserted(inserted, elapsed);
                case Duplicate duplicate -> onDuplicate(duplicate, elapsed);
            }
        } finally {
            MDC.remove(TenantContextAspect.MDC_TENANT_ID);
        }
    }

    private void onInserted(Inserted inserted, Duration elapsed) {
        metrics.eventRecorded(EventResult.INSERTED, elapsed);
        for (var crossing : inserted.crossings()) {
            metrics.thresholdCrossed(crossing.percent());
            log.info("Usage threshold {}% crossed for resource {} in period {}",
                    crossing.percent(), inserted.counter().resourceId(), inserted.counter().period());
        }
        if (inserted.late()) {
            metrics.lateEvent();
            log.warn("Late usage event recorded for already invoiced period {} (resource {}); "
                            + "the issued invoice does not include it",
                    inserted.counter().period(), inserted.counter().resourceId());
        }
        log.debug("Usage event recorded: resource {} period {} total {}",
                inserted.counter().resourceId(), inserted.counter().period(), inserted.snapshot().totalUnits());
    }

    private void onDuplicate(Duplicate duplicate, Duration elapsed) {
        metrics.eventRecorded(EventResult.DUPLICATE, elapsed);
        log.debug("Duplicate usage event ignored: resource {} period {} total stays {}",
                duplicate.counter().resourceId(), duplicate.counter().period(), duplicate.snapshot().totalUnits());
    }
}
