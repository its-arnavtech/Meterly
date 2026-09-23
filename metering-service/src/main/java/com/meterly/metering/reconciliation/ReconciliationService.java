package com.meterly.metering.reconciliation;

import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.tenant.TenantContext;
import com.meterly.common.web.RedactedStackTrace;
import com.meterly.metering.MeteringMetrics;
import com.meterly.metering.MeteringMetrics.Store;
import com.meterly.metering.catalog.TenantCatalogRepository;
import com.meterly.metering.catalog.TenantCatalogRepository.Resource;
import com.meterly.metering.counter.CounterKey;
import com.meterly.metering.counter.CounterSnapshot;
import com.meterly.metering.counter.CounterStateRepository;
import com.meterly.metering.counter.UsageCounterCache;
import com.meterly.metering.counter.UsageCounterCache.Cached;
import com.meterly.metering.counter.UsageLedgerRepository;
import com.meterly.metering.threshold.ThresholdEvaluator;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-derives every open-period counter from the ledger and corrects the projection and Redis when
 * either drifted (PDD 5.4). Drift should be zero in normal operation: this catches bugs and lost cache
 * state, it is not a routine correction path, so every correction is logged at WARN and counted in
 * {@code meterly.reconciliation.drift}.
 *
 * <p>One transaction per tenant (RLS-bound); each counter is checked under its advisory lock, so no
 * consumer can commit to that counter meanwhile, which makes the unconditional Redis repair safe.
 * A failing tenant is logged and counted, and the run continues. Concurrent runs are safe (the locks
 * serialize them), merely redundant.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private record TenantResult(int countersChecked, int countersCorrected) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantCatalogRepository catalog;
    private final UsageLedgerRepository ledger;
    private final CounterStateRepository counters;
    private final UsageCounterCache cache;
    private final ThresholdEvaluator thresholds;
    private final MeteringMetrics metrics;
    private final Clock clock;

    public ReconciliationService(JdbcClient jdbc, TransactionTemplate tx, TenantCatalogRepository catalog,
                                 UsageLedgerRepository ledger, CounterStateRepository counters,
                                 UsageCounterCache cache, ThresholdEvaluator thresholds, MeteringMetrics metrics,
                                 Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.catalog = catalog;
        this.ledger = ledger;
        this.counters = counters;
        this.cache = cache;
        this.thresholds = thresholds;
        this.metrics = metrics;
        this.clock = clock;
    }

    public ReconciliationSummary reconcileOpenPeriod() {
        BillingPeriod period = BillingPeriod.current(clock);
        List<UUID> tenants = jdbc.sql("SELECT tenant_id FROM system_active_tenant_ids() AS tenant_id")
                .query((rs, row) -> rs.getObject(1, UUID.class))
                .list();
        int tenantsChecked = 0;
        int countersChecked = 0;
        int countersCorrected = 0;
        for (UUID tenantId : tenants) {
            try {
                TenantResult result = TenantContext.callAs(tenantId,
                        () -> tx.execute(status -> reconcileTenant(tenantId, period)));
                tenantsChecked++;
                countersChecked += result.countersChecked();
                countersCorrected += result.countersCorrected();
            } catch (RuntimeException e) {
                metrics.reconciliationFailure();
                log.error("Reconciliation failed for tenant {} in period {}:{}", tenantId, period,
                        RedactedStackTrace.of(e));
            }
        }
        log.info("Reconciliation of period {}: {} tenants, {} counters checked, {} corrected",
                period, tenantsChecked, countersChecked, countersCorrected);
        return new ReconciliationSummary(tenantsChecked, countersChecked, countersCorrected);
    }

    private TenantResult reconcileTenant(UUID tenantId, BillingPeriod period) {
        int checked = 0;
        int corrected = 0;
        for (Resource resource : catalog.resources(tenantId)) {
            CounterKey key = new CounterKey(tenantId, resource.id(), period);
            checked++;
            if (reconcileCounter(key)) {
                corrected++;
            }
        }
        return new TenantResult(checked, corrected);
    }

    /** Returns true if the projection or Redis had to be corrected. */
    private boolean reconcileCounter(CounterKey key) {
        ledger.lock(key);
        CounterSnapshot truth = ledger.sum(key);

        Optional<CounterSnapshot> projected = counters.find(key);
        boolean projectionDrifted = projected.map(snapshot -> !snapshot.equals(truth))
                .orElse(truth.ledgerVersion() > 0);
        if (projectionDrifted) {
            counters.overwrite(key, truth);
            metrics.reconciliationDrift(Store.PROJECTION);
            // A drifted counter may have skipped a threshold; the marker table keeps this idempotent.
            thresholds.evaluate(key, truth.totalUnits()).forEach(crossing -> metrics.thresholdCrossed(crossing.percent()));
        }

        Cached cached = cache.read(key);
        boolean redisDrifted = switch (cached) {
            case Cached.Hit hit -> hit.totalUnits() != truth.totalUnits() || hit.ledgerVersion() != truth.ledgerVersion();
            case Cached.Miss miss -> truth.ledgerVersion() > 0;
            case Cached.Unavailable unavailable -> false;
        };
        if (redisDrifted) {
            cache.repair(key, truth);
            metrics.reconciliationDrift(Store.REDIS);
        }

        if (projectionDrifted || redisDrifted) {
            log.warn("Usage counter drift corrected: tenant {} resource {} period {} ledger={} projection={} redis={}",
                    key.tenantId(), key.resourceId(), key.period(), truth.totalUnits(),
                    projected.map(CounterSnapshot::totalUnits).map(String::valueOf).orElse("missing"),
                    describe(cached));
        }
        return projectionDrifted || redisDrifted;
    }

    private static String describe(Cached cached) {
        return switch (cached) {
            case Cached.Hit hit -> Long.toString(hit.totalUnits());
            case Cached.Miss miss -> "missing";
            case Cached.Unavailable unavailable -> "unavailable";
        };
    }
}
