package com.meterly.metering.usage;

import com.meterly.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "meterly.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class UsageReconciliationJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(UsageReconciliationJob.class);
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("uuuu-MM");

    private final ReconciliationRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public UsageReconciliationJob(
            ReconciliationRepository repository,
            StringRedisTemplate redisTemplate
    ) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.clock = Clock.systemUTC();
    }

    @Scheduled(cron = "${meterly.reconciliation.cron:0 0 2 * * *}", zone = "UTC")
    public ReconciliationSummary reconcileOpenPeriod() {
        LocalDate periodStartDate = LocalDate.now(clock).withDayOfMonth(1);
        LocalDate periodEndDate = periodStartDate.plusMonths(1);
        Instant periodStart = periodStartDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant periodEnd = periodEndDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        String period = PERIOD.format(periodStartDate);
        int tenantCount = 0;
        int resourceCount = 0;
        int driftCount = 0;

        for (UUID tenantId : repository.listActiveTenantIds()) {
            tenantCount++;
            try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
                for (ReconciliationRepository.ResourceTotal total
                        : repository.totalsForPeriod(periodStart, periodEnd)) {
                    resourceCount++;
                    String key = UsageMeteringService.redisKey(tenantId, total.resourceId(), period);
                    String actualValue = redisTemplate.opsForValue().get(key);
                    Long actual = parseCounter(actualValue);
                    if (actual == null || actual != total.totalUnits()) {
                        driftCount++;
                        LOGGER.warn(
                                "usage_reconciliation_drift tenant_id={} resource_id={} period={} expected={} actual={}",
                                tenantId, total.resourceId(), period, total.totalUnits(),
                                actualValue == null ? "missing" : actualValue
                        );
                        redisTemplate.opsForValue().set(key, Long.toString(total.totalUnits()));
                    }
                }
            }
        }
        return new ReconciliationSummary(tenantCount, resourceCount, driftCount);
    }

    private Long parseCounter(String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public record ReconciliationSummary(int tenantCount, int resourceCount, int driftCount) { }
}
