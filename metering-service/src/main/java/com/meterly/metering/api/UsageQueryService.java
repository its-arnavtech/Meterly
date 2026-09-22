package com.meterly.metering.api;

import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.web.ApiException;
import com.meterly.metering.api.UsageResponses.CurrentUsage;
import com.meterly.metering.api.UsageResponses.Item;
import com.meterly.metering.api.UsageResponses.Plan;
import com.meterly.metering.api.UsageResponses.Point;
import com.meterly.metering.api.UsageResponses.Timeseries;
import com.meterly.metering.catalog.TenantCatalogRepository;
import com.meterly.metering.catalog.TenantCatalogRepository.PlanSummary;
import com.meterly.metering.catalog.TenantCatalogRepository.Resource;
import com.meterly.metering.counter.CounterKey;
import com.meterly.metering.counter.CounterSnapshot;
import com.meterly.metering.counter.CounterStateRepository;
import com.meterly.metering.counter.UsageCounterCache;
import com.meterly.metering.counter.UsageLedgerRepository;
import com.meterly.metering.counter.UsageLedgerRepository.BucketTotal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Read side of the usage API. Current counters come from Redis first; misses fall back to the
 * transactional projection, then to a ledger {@code SUM}, and are re-seeded into Redis through the same
 * version-guarded script the consumer uses. Time series always come from the ledger (source of truth).
 * All database reads run in read-only transactions bound to the caller's tenant (RLS).
 */
@Service
public class UsageQueryService {

    private record Catalog(List<Resource> activeResources, PlanSummary plan) {
    }

    private final TransactionTemplate readOnlyTx;
    private final TenantCatalogRepository catalog;
    private final CounterStateRepository counters;
    private final UsageLedgerRepository ledger;
    private final UsageCounterCache cache;
    private final Clock clock;

    public UsageQueryService(PlatformTransactionManager transactionManager, TenantCatalogRepository catalog,
                             CounterStateRepository counters, UsageLedgerRepository ledger, UsageCounterCache cache,
                             Clock clock) {
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.catalog = catalog;
        this.counters = counters;
        this.ledger = ledger;
        this.cache = cache;
        this.clock = clock;
    }

    public CurrentUsage current(UUID tenantId) {
        BillingPeriod period = BillingPeriod.current(clock);
        Catalog tenantCatalog = readOnlyTx.execute(status -> new Catalog(
                catalog.resources(tenantId).stream().filter(Resource::active).toList(),
                catalog.plan(tenantId, period).orElse(PlanSummary.NONE)));
        List<UUID> resourceIds = tenantCatalog.activeResources().stream().map(Resource::id).toList();

        Map<UUID, Long> totals = new HashMap<>(cache.readTotals(tenantId, period, resourceIds));
        List<UUID> misses = resourceIds.stream().filter(id -> !totals.containsKey(id)).toList();
        if (!misses.isEmpty()) {
            Map<UUID, CounterSnapshot> recovered = readOnlyTx.execute(status -> fromDatabase(tenantId, period, misses));
            recovered.forEach((resourceId, snapshot) -> {
                totals.put(resourceId, snapshot.totalUnits());
                cache.project(new CounterKey(tenantId, resourceId, period), snapshot, null);
            });
        }

        long includedUnits = tenantCatalog.plan().includedUnits();
        List<Item> items = new ArrayList<>();
        for (Resource resource : tenantCatalog.activeResources()) {
            long total = totals.getOrDefault(resource.id(), 0L);
            items.add(new Item(resource.id(), resource.name(), resource.unitLabel(), total, includedUnits,
                    percentOf(total, includedUnits)));
        }
        PlanSummary plan = tenantCatalog.plan();
        return new CurrentUsage(period.start(), period.end(), new Plan(plan.code(), plan.name(), includedUnits), items);
    }

    public Timeseries timeseries(UUID tenantId, TimeseriesQuery query) {
        List<BucketTotal> buckets = readOnlyTx.execute(status -> {
            if (catalog.resource(tenantId, query.resourceId()).isEmpty()) {
                throw ApiException.notFound("RESOURCE_NOT_FOUND", "Resource not found");
            }
            return ledger.buckets(tenantId, query.resourceId(), query.granularity().label(), query.from(), query.to());
        });
        Map<Instant, Long> unitsByBucket = new HashMap<>();
        for (BucketTotal bucket : buckets) {
            unitsByBucket.put(bucket.bucketStart(), bucket.units());
        }
        List<Point> points = query.bucketStarts().stream()
                .map(start -> new Point(start, unitsByBucket.getOrDefault(start, 0L)))
                .toList();
        return new Timeseries(query.resourceId(), query.granularity().label(), query.from(), query.to(), points);
    }

    /** Projection rows for the missed counters; a ledger SUM for counters that have no projection row. */
    private Map<UUID, CounterSnapshot> fromDatabase(UUID tenantId, BillingPeriod period, List<UUID> resourceIds) {
        Map<UUID, CounterSnapshot> projected = counters.findForPeriod(tenantId, period);
        Map<UUID, CounterSnapshot> snapshots = new HashMap<>();
        for (UUID resourceId : resourceIds) {
            CounterSnapshot snapshot = projected.get(resourceId);
            if (snapshot == null) {
                snapshot = ledger.sum(new CounterKey(tenantId, resourceId, period));
            }
            snapshots.put(resourceId, snapshot);
        }
        return snapshots;
    }

    /** Percentage of the allowance, rounded half-up to one decimal; 0 when the plan has no allowance. */
    static double percentOf(long totalUnits, long includedUnits) {
        if (includedUnits <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(totalUnits)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(includedUnits), 1, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
