package com.meterly.billing.run;

import com.meterly.billing.BillingErrors;
import com.meterly.common.billing.BillingPeriod;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

/**
 * Resolves and validates billing periods against the injected {@link Clock} (UTC calendar months,
 * {@code [period_start, period_end)}).
 */
@Component
public class BillingPeriods {

    private final Clock clock;

    public BillingPeriods(Clock clock) {
        this.clock = clock;
    }

    /** The open period containing "now". */
    public BillingPeriod current() {
        return BillingPeriod.current(clock);
    }

    /** The most recently closed period. */
    public BillingPeriod previous() {
        return current().previous();
    }

    /**
     * Parses an optional {@code period_start}: absent means the previous month; otherwise it must be an ISO
     * date on the 1st of a month ({@code 400 INVALID_PERIOD}). Whether the period is closed is checked by
     * {@link #requireClosed}.
     */
    public BillingPeriod resolve(String periodStart) {
        if (periodStart == null || periodStart.isBlank()) {
            return previous();
        }
        LocalDate start;
        try {
            start = LocalDate.parse(periodStart.strip());
        } catch (DateTimeParseException e) {
            throw BillingErrors.invalidPeriod("period_start must be an ISO date such as 2026-08-01");
        }
        if (start.getDayOfMonth() != 1) {
            throw BillingErrors.invalidPeriod("period_start must be the first day of a month");
        }
        return BillingPeriod.startingOn(start);
    }

    /** {@code 422 PERIOD_NOT_CLOSED} unless {@code period_end <= now}. */
    public void requireClosed(BillingPeriod period) {
        if (!period.isClosedAt(clock.instant())) {
            throw BillingErrors.periodNotClosed(period);
        }
    }
}
