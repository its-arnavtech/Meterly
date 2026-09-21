package com.meterly.common.billing;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A billing period: one calendar month in UTC, as the half-open interval {@code [start, end)}.
 *
 * <p>Every service derives periods from this one type so the metering counters, the reconciliation
 * job, the billing run and the invoices always agree on which month an event belongs to.
 */
public record BillingPeriod(LocalDate start, LocalDate end) implements Comparable<BillingPeriod> {

    public BillingPeriod {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (start.getDayOfMonth() != 1) {
            throw new IllegalArgumentException("Billing period must start on the first day of a month: " + start);
        }
        if (!end.equals(start.plusMonths(1))) {
            throw new IllegalArgumentException("Billing period must span exactly one month: " + start + " to " + end);
        }
    }

    public static BillingPeriod of(YearMonth month) {
        LocalDate start = month.atDay(1);
        return new BillingPeriod(start, start.plusMonths(1));
    }

    /** Parses a period start such as {@code 2026-08-01}; rejects dates that are not the 1st. */
    public static BillingPeriod startingOn(LocalDate start) {
        return new BillingPeriod(start, start.plusMonths(1));
    }

    public static BillingPeriod containing(Instant instant) {
        return of(YearMonth.from(instant.atOffset(ZoneOffset.UTC)));
    }

    public static BillingPeriod current(Clock clock) {
        return containing(clock.instant());
    }

    public YearMonth yearMonth() {
        return YearMonth.from(start);
    }

    public Instant startInstant() {
        return start.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    public Instant endInstant() {
        return end.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(startInstant()) && instant.isBefore(endInstant());
    }

    /** A period is closed once its exclusive end has been reached. */
    public boolean isClosedAt(Instant now) {
        return !now.isBefore(endInstant());
    }

    public BillingPeriod previous() {
        return of(yearMonth().minusMonths(1));
    }

    public BillingPeriod next() {
        return of(yearMonth().plusMonths(1));
    }

    @Override
    public int compareTo(BillingPeriod other) {
        return start.compareTo(other.start);
    }

    @Override
    public String toString() {
        return yearMonth().toString();
    }
}
