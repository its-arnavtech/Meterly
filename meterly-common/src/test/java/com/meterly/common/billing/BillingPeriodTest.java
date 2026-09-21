package com.meterly.common.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class BillingPeriodTest {

    @Test
    void containingUsesUtcMonthBoundaries() {
        // 23:30 on Aug 31 in UTC-5 is already Sep 1 in UTC.
        BillingPeriod period = BillingPeriod.containing(Instant.parse("2026-09-01T04:30:00Z"));
        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(BillingPeriod.containing(Instant.parse("2026-08-31T23:59:59.999Z")).yearMonth())
                .isEqualTo(YearMonth.of(2026, 8));
    }

    @Test
    void intervalIsHalfOpen() {
        BillingPeriod aug = BillingPeriod.of(YearMonth.of(2026, 8));
        assertThat(aug.contains(Instant.parse("2026-08-01T00:00:00Z"))).isTrue();
        assertThat(aug.contains(Instant.parse("2026-09-01T00:00:00Z"))).isFalse();
    }

    @Test
    void closedOnceEndIsReached() {
        BillingPeriod aug = BillingPeriod.of(YearMonth.of(2026, 8));
        assertThat(aug.isClosedAt(Instant.parse("2026-08-31T23:59:59Z"))).isFalse();
        assertThat(aug.isClosedAt(Instant.parse("2026-09-01T00:00:00Z"))).isTrue();
    }

    @Test
    void navigatesAcrossYearBoundaries() {
        BillingPeriod jan = BillingPeriod.of(YearMonth.of(2027, 1));
        assertThat(jan.previous().yearMonth()).isEqualTo(YearMonth.of(2026, 12));
        assertThat(jan.previous().next()).isEqualTo(jan);
    }

    @Test
    void rejectsPeriodsNotStartingOnTheFirst() {
        assertThatThrownBy(() -> BillingPeriod.startingOn(LocalDate.of(2026, 8, 15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BillingPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
