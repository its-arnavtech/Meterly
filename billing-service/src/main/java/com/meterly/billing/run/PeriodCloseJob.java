package com.meterly.billing.run;

import com.meterly.billing.BillingErrors;
import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.web.ApiException;
import com.meterly.common.web.RedactedStackTrace;
import java.time.LocalDate;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Period close: bills a closed period for every active tenant, one transaction per tenant. A failing
 * tenant is logged and counted but never stops the others; re-running is safe because each tenant's run is
 * idempotent. Triggered by cron (once across replicas, via ShedLock) or by
 * {@code POST /internal/v1/billing/run-all}.
 */
@Component
public class PeriodCloseJob {

    private static final Logger log = LoggerFactory.getLogger(PeriodCloseJob.class);

    private final BillingService billing;
    private final BillingRunRepository runs;
    private final BillingPeriods periods;

    public PeriodCloseJob(BillingService billing, BillingRunRepository runs, BillingPeriods periods) {
        this.billing = billing;
        this.runs = runs;
        this.periods = periods;
    }

    /** Totals of one run-all pass ({@code POST /internal/v1/billing/run-all} response). */
    public record Summary(LocalDate periodStart, int created, int alreadyInvoiced, int skippedNoSubscription,
                          int failed) {
    }

    @Scheduled(cron = "${meterly.billing.close-cron:0 0 2 1 * *}", zone = "UTC")
    @SchedulerLock(name = "billing-period-close", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    public void closePreviousPeriod() {
        runAll(periods.previous());
    }

    /** Bills {@code period} for every active tenant; {@code 422 PERIOD_NOT_CLOSED} if it is still open. */
    public Summary runAll(BillingPeriod period) {
        periods.requireClosed(period);
        int created = 0;
        int alreadyInvoiced = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID tenantId : runs.activeTenantIds()) {
            try {
                switch (billing.run(tenantId, period)) {
                    case BillingRunResult.Created ignored -> created++;
                    case BillingRunResult.AlreadyInvoiced ignored -> alreadyInvoiced++;
                }
            } catch (ApiException e) {
                if (BillingErrors.isNoSubscription(e)) {
                    skipped++;
                } else {
                    failed++;
                    log.error("Billing run for tenant {} period {} failed with {}", tenantId, period, e.code());
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("Billing run for tenant {} period {} failed:{}", tenantId, period, RedactedStackTrace.of(e));
            }
        }
        Summary summary = new Summary(period.start(), created, alreadyInvoiced, skipped, failed);
        log.info("Period close {}: {} created, {} already invoiced, {} without subscription, {} failed",
                period, created, alreadyInvoiced, skipped, failed);
        return summary;
    }
}
