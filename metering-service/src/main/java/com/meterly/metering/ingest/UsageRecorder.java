package com.meterly.metering.ingest;

import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.event.UsageEventMessage;
import com.meterly.common.event.UsageUpdate;
import com.meterly.common.tenant.TenantContext;
import com.meterly.metering.catalog.TenantCatalogRepository;
import com.meterly.metering.counter.CounterKey;
import com.meterly.metering.counter.CounterSnapshot;
import com.meterly.metering.counter.CounterStateRepository;
import com.meterly.metering.counter.UsageCounterCache;
import com.meterly.metering.counter.UsageLedgerRepository;
import com.meterly.metering.counter.UsageLedgerRepository.LedgerRow;
import com.meterly.metering.counter.UsageLedgerRepository.RecordedEvent;
import com.meterly.metering.ingest.RecordingOutcome.Duplicate;
import com.meterly.metering.ingest.RecordingOutcome.Inserted;
import com.meterly.metering.threshold.ThresholdEvaluator;
import com.meterly.metering.threshold.ThresholdEvaluator.Crossing;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records one usage event exactly once, then projects the committed counter into Redis.
 *
 * <p>Inside one transaction bound to the event's tenant (RLS):
 * <ol>
 *   <li>take the counter's transaction-scoped advisory lock (tenant, resource, period), so ledger ids and
 *       projection versions of one counter follow commit order;</li>
 *   <li>{@code INSERT ... ON CONFLICT (tenant_id, idempotency_key) DO NOTHING RETURNING id};</li>
 *   <li>only if a row was inserted: add it to {@code usage_counter_state} and evaluate usage thresholds
 *       (marker + outbox row).</li>
 * </ol>
 * After COMMIT the snapshot goes to Redis through the version-guarded script, which also publishes the
 * {@link UsageUpdate} for live dashboards. A duplicate changes nothing; it re-projects the committed
 * snapshot, which repairs a projection lost to a crash between COMMIT and the Redis write.
 */
@Service
public class UsageRecorder {

    private final TransactionTemplate tx;
    private final UsageLedgerRepository ledger;
    private final CounterStateRepository counters;
    private final ThresholdEvaluator thresholds;
    private final TenantCatalogRepository catalog;
    private final UsageCounterCache cache;
    private final Clock clock;

    public UsageRecorder(TransactionTemplate tx, UsageLedgerRepository ledger, CounterStateRepository counters,
                         ThresholdEvaluator thresholds, TenantCatalogRepository catalog, UsageCounterCache cache,
                         Clock clock) {
        this.tx = tx;
        this.ledger = ledger;
        this.counters = counters;
        this.thresholds = thresholds;
        this.catalog = catalog;
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * Records the event and projects the result. Database failures propagate (the Kafka error handler
     * retries or dead-letters the record); Redis failures after COMMIT never do.
     */
    public RecordingOutcome record(UsageEventMessage event) {
        RecordingOutcome outcome = TenantContext.callAs(event.tenantId(),
                () -> tx.execute(status -> recordInTransaction(event)));
        project(event, outcome);
        return outcome;
    }

    private RecordingOutcome recordInTransaction(UsageEventMessage event) {
        CounterKey counter = new CounterKey(event.tenantId(), event.resourceId(),
                BillingPeriod.containing(event.occurredAt()));
        ledger.lock(counter);
        Optional<LedgerRow> inserted = ledger.insert(event);
        if (inserted.isEmpty()) {
            return duplicateOf(event);
        }
        LedgerRow row = inserted.get();
        CounterSnapshot snapshot = counters.increment(counter, event.quantity(), row.id());
        List<Crossing> crossings = thresholds.evaluate(counter, snapshot.totalUnits());
        boolean late = !counter.period().equals(BillingPeriod.current(clock))
                && catalog.invoiceExists(event.tenantId(), counter.period());
        return new Inserted(counter, snapshot, event.quantity(), row.recordedAt(), late, crossings);
    }

    /** Resolves the counter the original event was counted in (it may differ from the retry's payload). */
    private Duplicate duplicateOf(UsageEventMessage event) {
        RecordedEvent original = ledger.findByIdempotencyKey(event.tenantId(), event.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Conflicting ledger row is not visible"));
        CounterKey counter = new CounterKey(event.tenantId(), original.resourceId(),
                BillingPeriod.containing(original.occurredAt()));
        CounterSnapshot snapshot = counters.find(counter).orElseGet(() -> ledger.sum(counter));
        return new Duplicate(counter, snapshot);
    }

    private void project(UsageEventMessage event, RecordingOutcome outcome) {
        switch (outcome) {
            case Inserted inserted -> {
                UsageUpdate update = new UsageUpdate(event.tenantId(), event.resourceId(),
                        inserted.counter().period().start(), inserted.snapshot().totalUnits(), inserted.delta(),
                        event.occurredAt(), inserted.recordedAt());
                cache.project(inserted.counter(), inserted.snapshot(), update);
            }
            case Duplicate duplicate -> cache.project(duplicate.counter(), duplicate.snapshot(), null);
        }
    }
}
