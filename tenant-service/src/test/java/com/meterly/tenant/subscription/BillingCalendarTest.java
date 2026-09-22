package com.meterly.tenant.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Subscription date math: UTC days, and plan changes at the next calendar-month boundary. */
class BillingCalendarTest {

    private static BillingCalendar at(String instant, ZoneId zone) {
        return new BillingCalendar(Clock.fixed(Instant.parse(instant), zone));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-09-21T17:04:05Z, 2026-09-21, 2026-10-01",
            "2026-09-01T00:00:00Z, 2026-09-01, 2026-10-01",
            "2026-09-30T23:59:59Z, 2026-09-30, 2026-10-01",
            "2026-12-31T23:59:59Z, 2026-12-31, 2027-01-01",
            "2027-01-01T00:00:00Z, 2027-01-01, 2027-02-01",
            "2028-02-29T12:00:00Z, 2028-02-29, 2028-03-01",
            "2026-01-31T12:00:00Z, 2026-01-31, 2026-02-01"})
    void computesTodayAndTheNextPeriodStart(String now, LocalDate expectedToday, LocalDate expectedNextStart) {
        BillingCalendar calendar = at(now, ZoneOffset.UTC);

        assertThat(calendar.today()).isEqualTo(expectedToday);
        assertThat(calendar.nextPeriodStart()).isEqualTo(expectedNextStart);
    }

    @Test
    void usesUtcDaysEvenWhenTheClockIsInAnotherZone() {
        // 2026-09-30 20:00 in New York is already 2026-10-01 in UTC.
        BillingCalendar calendar = at("2026-10-01T00:00:00Z", ZoneId.of("America/New_York"));

        assertThat(calendar.today()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(calendar.nextPeriodStart()).isEqualTo(LocalDate.of(2026, 11, 1));
    }
}
