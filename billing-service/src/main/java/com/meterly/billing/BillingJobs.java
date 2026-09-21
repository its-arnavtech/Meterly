package com.meterly.billing;

import com.meterly.common.tenant.TenantContext;
import com.meterly.common.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BillingJobs {
    private static final Logger LOG = LoggerFactory.getLogger(BillingJobs.class);
    public record RunSummary(LocalDate periodStart, int created, int alreadyInvoiced, int skippedNoSubscription, int failed) {}
    private final JdbcTemplate jdbc;
    private final BillingService service;
    private final MeterRegistry metrics;
    public BillingJobs(JdbcTemplate jdbc, BillingService service, MeterRegistry metrics) {
        this.jdbc = jdbc; this.service = service; this.metrics = metrics;
    }
    @Scheduled(cron = "${meterly.billing.close-cron:0 0 2 1 * *}", zone = "UTC")
    @SchedulerLock(name = "billing-period-close", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void scheduledClose() { runAll(null); }

    public RunSummary runAll(LocalDate start) {
        var period = service.closedPeriod(start);
        int created = 0, already = 0, skipped = 0, failed = 0;
        for (UUID tenant : jdbc.query("SELECT * FROM system_active_tenant_ids()", (rs, n) -> rs.getObject(1, UUID.class))) {
            String outcome;
            try {
                var result = TenantContext.callAs(tenant, () -> service.run(period.start()));
                if (result.created()) { created++; outcome = "created"; }
                else { already++; outcome = "already_invoiced"; }
            } catch (ApiException e) {
                if (e.code().equals("NO_SUBSCRIPTION")) { skipped++; outcome = "skipped_no_subscription"; }
                else { failed++; outcome = "failed"; LOG.error("Billing failed for tenant {} period {} code {}", tenant, period, e.code()); }
            } catch (RuntimeException e) {
                failed++; outcome = "failed";
                LOG.error("Billing failed for tenant {} period {} exception {}", tenant, period, e.getClass().getSimpleName());
            }
            metrics.counter("meterly_billing_runs_total", "outcome", outcome).increment();
        }
        return new RunSummary(period.start(), created, already, skipped, failed);
    }
}
