package com.meterly.metering.reconciliation;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly reconciliation (03:00 UTC by default), executed by exactly one replica thanks to ShedLock. */
@Component
public class ReconciliationJob {

    private final ReconciliationService reconciliation;

    public ReconciliationJob(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(cron = "${meterly.metering.reconciliation-cron:0 0 3 * * *}", zone = "UTC")
    @SchedulerLock(name = "metering-reconciliation", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void reconcileNightly() {
        reconciliation.reconcileOpenPeriod();
    }
}
