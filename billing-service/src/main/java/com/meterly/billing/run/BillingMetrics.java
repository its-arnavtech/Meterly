package com.meterly.billing.run;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Billing run metrics: {@code meterly.billing.runs{outcome}} (Prometheus {@code meterly_billing_runs_total})
 * and {@code meterly.billing.run.duration{outcome}}. All outcomes are registered up front so dashboards
 * see zeros rather than missing series.
 */
@Component
public class BillingMetrics {

    static final String RUNS = "meterly.billing.runs";
    static final String RUN_DURATION = "meterly.billing.run.duration";

    /** Outcome of one tenant-period run, as the {@code outcome} tag. */
    public enum Outcome {
        CREATED("created"),
        ALREADY_INVOICED("already_invoiced"),
        SKIPPED_NO_SUBSCRIPTION("skipped_no_subscription"),
        FAILED("failed");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }

        static Outcome of(BillingRunResult result) {
            return switch (result) {
                case BillingRunResult.Created created -> CREATED;
                case BillingRunResult.AlreadyInvoiced alreadyInvoiced -> ALREADY_INVOICED;
            };
        }
    }

    private final MeterRegistry registry;
    private final Map<Outcome, Counter> runs = new EnumMap<>(Outcome.class);
    private final Map<Outcome, Timer> durations = new EnumMap<>(Outcome.class);

    public BillingMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (Outcome outcome : Outcome.values()) {
            runs.put(outcome, Counter.builder(RUNS).description("Billing runs per tenant and period")
                    .tag("outcome", outcome.tag()).register(registry));
            durations.put(outcome, Timer.builder(RUN_DURATION).description("Duration of one tenant's billing run")
                    .tag("outcome", outcome.tag()).register(registry));
        }
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void record(Timer.Sample sample, Outcome outcome) {
        sample.stop(durations.get(outcome));
        runs.get(outcome).increment();
    }
}
