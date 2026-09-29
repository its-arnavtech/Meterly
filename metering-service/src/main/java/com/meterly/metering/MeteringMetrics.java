package com.meterly.metering;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Every Micrometer meter of metering-service in one place, so names and tags stay consistent.
 * Prometheus renders {@code meterly.metering.events} as {@code meterly_metering_events_total}, etc.
 */
@Component
public class MeteringMetrics {

    public static final String EVENTS = "meterly.metering.events";
    public static final String PROCESSING = "meterly.metering.processing";
    public static final String DEAD_LETTERS = "meterly.metering.dead_letters";
    public static final String REDIS_FAILURES = "meterly.metering.redis_failures";
    public static final String LATE_EVENTS = "meterly.metering.late_events";
    public static final String THRESHOLD_EVENTS = "meterly.metering.threshold_events";
    public static final String OUTBOX_PUBLISHED = "meterly.metering.outbox.published";
    public static final String OUTBOX_FAILED = "meterly.metering.outbox.failed";
    public static final String LIVE_UPDATES = "meterly.metering.live_updates";
    public static final String RECONCILIATION_DRIFT = "meterly.reconciliation.drift";
    public static final String RECONCILIATION_FAILURES = "meterly.reconciliation.failures";

    /** Outcome of recording one usage event. */
    public enum EventResult {
        INSERTED,
        DUPLICATE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Which store a reconciliation run found out of line with the ledger. */
    public enum Store {
        PROJECTION,
        REDIS;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;
    private final Counter lateEvents;
    private final Counter outboxPublished;
    private final Counter outboxFailed;
    private final Counter reconciliationFailures;

    public MeteringMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.lateEvents = Counter.builder(LATE_EVENTS)
                .description("Usage events recorded for a period that was already invoiced")
                .register(registry);
        this.outboxPublished = Counter.builder(OUTBOX_PUBLISHED)
                .description("Threshold events relayed from the outbox to Kafka")
                .register(registry);
        this.outboxFailed = Counter.builder(OUTBOX_FAILED)
                .description("Outbox relay attempts that failed (retried on the next run)")
                .register(registry);
        this.reconciliationFailures = Counter.builder(RECONCILIATION_FAILURES)
                .description("Tenants whose reconciliation failed")
                .register(registry);
        for (EventResult result : EventResult.values()) {
            eventsCounter(result);
        }
    }

    public void eventRecorded(EventResult result, Duration processingTime) {
        eventsCounter(result).increment();
        Timer.builder(PROCESSING)
                .description("Time to record one usage event (parse, transaction, projection)")
                .tag("result", result.tag())
                .register(registry)
                .record(processingTime);
    }

    public void deadLettered(String reason) {
        Counter.builder(DEAD_LETTERS)
                .description("Usage event records sent to the dead-letter topic")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void redisFailure(String operation) {
        Counter.builder(REDIS_FAILURES)
                .description("Redis operations that failed; PostgreSQL stays authoritative")
                .tag("operation", operation)
                .register(registry)
                .increment();
    }

    public void lateEvent() {
        lateEvents.increment();
    }

    public void thresholdCrossed(int percent) {
        Counter.builder(THRESHOLD_EVENTS)
                .description("Usage threshold crossings written to the outbox")
                .tag("percent", Integer.toString(percent))
                .register(registry)
                .increment();
    }

    public void outboxPublished(int count) {
        outboxPublished.increment(count);
    }

    public void outboxFailed() {
        outboxFailed.increment();
    }

    public void liveUpdate(String result) {
        Counter.builder(LIVE_UPDATES)
                .description("Usage updates received from Redis pub/sub and forwarded to STOMP")
                .tag("result", result)
                .register(registry)
                .increment();
    }

    public void reconciliationDrift(Store store) {
        Counter.builder(RECONCILIATION_DRIFT)
                .description("Counters found out of line with the ledger and corrected")
                .tag("store", store.tag())
                .register(registry)
                .increment();
    }

    public void reconciliationFailure() {
        reconciliationFailures.increment();
    }

    private Counter eventsCounter(EventResult result) {
        return Counter.builder(EVENTS)
                .description("Usage events consumed, by result")
                .tag("result", result.tag())
                .register(registry);
    }
}
