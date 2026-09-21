package com.meterly.metering;

import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ReconciliationService {
    record Result(int tenantsChecked,int countersChecked,int countersCorrected) {}
    private static final Logger log=LoggerFactory.getLogger(ReconciliationService.class);
    private final UsageRepository repository;
    private final UsageCache cache;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry metrics;
    ReconciliationService(UsageRepository repository,UsageCache cache,TransactionTemplate tx,Clock clock,MeterRegistry metrics) {
        this.repository=repository; this.cache=cache; this.tx=tx; this.clock=clock; this.metrics=metrics;
    }
    @Scheduled(cron="${meterly.metering.reconciliation-cron:0 0 3 * * *}",zone="UTC")
    @SchedulerLock(name="metering-reconciliation",lockAtMostFor="PT30M")
    public void nightly() { run(); }
    Result run() {
        var tenants=repository.activeTenants(); BillingPeriod period=BillingPeriod.current(clock);
        int checked=0, corrected=0;
        for (UUID tenant:tenants) {
            try {
                var resources=TenantContext.callAs(tenant,()->tx.execute(s->repository.resources(false)));
                for (var resource:resources) {
                    Long before=cache.read(tenant,resource.id(),period);
                    var snapshot=TenantContext.callAs(tenant,()->tx.execute(s->repository.reconcile(resource.id(),period)));
                    cache.project(tenant,resource.id(),period,snapshot,null); checked++;
                    if (before==null || before.longValue()!=snapshot.units()) {
                        corrected++; metrics.counter("meterly_reconciliation_drift_total").increment();
                        log.warn("Usage cache drift repaired tenant={} resource={} period={} cached={} ledger={}",tenant,resource.id(),period,before,snapshot.units());
                    }
                }
            } catch (RuntimeException e) { metrics.counter("meterly_reconciliation_failures_total").increment(); log.error("Reconciliation failed tenant={}",tenant,e); }
        }
        return new Result(tenants.size(),checked,corrected);
    }
}
