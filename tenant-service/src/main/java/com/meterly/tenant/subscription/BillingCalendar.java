package com.meterly.tenant.subscription;

import com.meterly.common.billing.BillingPeriod;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * Calendar rules for subscriptions: "today" is the UTC date, billing periods are UTC calendar months
 * ({@link BillingPeriod}), and plan changes take effect at the start of the next period.
 */
@Component
public class BillingCalendar {

    private final Clock clock;

    public BillingCalendar(Clock clock) {
        this.clock = clock;
    }

    /** The current date in UTC, whatever the clock's own zone. */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** The first day of the billing period after the one containing {@code today()}. */
    public LocalDate nextPeriodStart() {
        return nextPeriodStart(today());
    }

    static LocalDate nextPeriodStart(LocalDate day) {
        return BillingPeriod.of(YearMonth.from(day)).end();
    }
}
