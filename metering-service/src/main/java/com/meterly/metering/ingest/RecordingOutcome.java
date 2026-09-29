package com.meterly.metering.ingest;

import com.meterly.metering.counter.CounterKey;
import com.meterly.metering.counter.CounterSnapshot;
import com.meterly.metering.threshold.ThresholdEvaluator.Crossing;
import java.time.Instant;
import java.util.List;

/**
 * The committed result of recording one usage event. Sealed, so every consumer of the outcome (Redis
 * projection, metrics, logging) handles both cases with an exhaustive {@code switch}.
 */
public sealed interface RecordingOutcome {

    /** The counter this outcome refers to. */
    CounterKey counter();

    /** The committed counter snapshot to project into Redis. */
    CounterSnapshot snapshot();

    /**
     * A new ledger row was inserted and counted.
     *
     * @param delta      the event's quantity
     * @param recordedAt the ledger row's {@code recorded_at}
     * @param late       the period was already invoiced (the row still counts toward the ledger)
     * @param crossings  thresholds crossed by this event (written to the outbox in the same transaction)
     */
    record Inserted(CounterKey counter, CounterSnapshot snapshot, long delta, Instant recordedAt, boolean late,
                    List<Crossing> crossings) implements RecordingOutcome {

        public Inserted {
            crossings = List.copyOf(crossings);
        }
    }

    /**
     * The idempotency key was already recorded; nothing changed. {@code counter} is the counter the
     * original event was counted in, and {@code snapshot} its committed state (re-projected idempotently).
     */
    record Duplicate(CounterKey counter, CounterSnapshot snapshot) implements RecordingOutcome {
    }
}
