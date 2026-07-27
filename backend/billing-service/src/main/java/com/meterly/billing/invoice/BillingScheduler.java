package com.meterly.billing.invoice;

import com.meterly.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "meterly.billing.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class BillingScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(BillingScheduler.class);

    private final BillingService billingService;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public BillingScheduler(BillingService billingService, JdbcTemplate jdbcTemplate) {
        this(billingService, jdbcTemplate, Clock.systemUTC());
    }

    BillingScheduler(BillingService billingService, JdbcTemplate jdbcTemplate, Clock clock) {
        this.billingService = billingService;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Scheduled(cron = "${meterly.billing.scheduler.cron:0 0 3 1 * *}", zone = "UTC")
    public BillingRunSummary closePreviousPeriod() {
        LocalDate periodEnd = LocalDate.now(clock).withDayOfMonth(1);
        LocalDate periodStart = periodEnd.minusMonths(1);
        List<UUID> tenantIds = jdbcTemplate.queryForList(
                "SELECT * FROM system_active_tenant_ids()", UUID.class);
        int completed = 0;
        for (UUID tenantId : tenantIds) {
            try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
                InvoiceView invoice = billingService.run(tenantId, periodStart, periodEnd);
                completed++;
                LOGGER.info(
                        "billing_period_closed tenant_id={} invoice_id={} period_start={} period_end={} total_amount={}",
                        tenantId, invoice.id(), periodStart, periodEnd, invoice.totalAmount()
                );
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "billing_period_close_failed tenant_id={} period_start={} period_end={}",
                        tenantId, periodStart, periodEnd, exception
                );
            }
        }
        return new BillingRunSummary(tenantIds.size(), completed, tenantIds.size() - completed);
    }

    public record BillingRunSummary(int tenantCount, int completedCount, int failedCount) { }
}
