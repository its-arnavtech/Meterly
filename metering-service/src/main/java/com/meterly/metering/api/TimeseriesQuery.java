package com.meterly.metering.api;

import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.web.ApiException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * A validated {@code GET /v1/usage/timeseries} query with defaults applied. Buckets are UTC hours or
 * days; the range is the half-open interval {@code [from, to)}.
 *
 * <p>Defaults: {@code day} granularity over the current billing period; for {@code hour} the last 24
 * hours up to the end of the current hour. Limits: 93 days ({@code day}), 7 days ({@code hour}).
 * {@code from}/{@code to} accept ISO-8601 instants ({@code 2026-09-01T00:00:00Z}) or dates
 * ({@code 2026-09-01}, meaning midnight UTC). Violations are {@code 400 VALIDATION_FAILED}.
 */
public record TimeseriesQuery(UUID resourceId, Granularity granularity, Instant from, Instant to) {

    public enum Granularity {
        HOUR(ChronoUnit.HOURS, Duration.ofDays(7)),
        DAY(ChronoUnit.DAYS, Duration.ofDays(93));

        private final ChronoUnit unit;
        private final Duration maxRange;

        Granularity(ChronoUnit unit, Duration maxRange) {
            this.unit = unit;
            this.maxRange = maxRange;
        }

        /** Lower-case name, as used in the API and as PostgreSQL {@code date_trunc} field. */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        public Duration maxRange() {
            return maxRange;
        }

        Instant truncate(Instant instant) {
            return instant.truncatedTo(unit);
        }

        Instant next(Instant bucketStart) {
            return bucketStart.plus(1, unit);
        }
    }

    public static TimeseriesQuery parse(String resourceId, String granularity, String from, String to, Clock clock) {
        UUID resource = parseResource(resourceId);
        Granularity unit = parseGranularity(granularity);
        Instant now = clock.instant();
        Instant defaultTo = switch (unit) {
            case DAY -> BillingPeriod.current(clock).endInstant();
            case HOUR -> unit.next(unit.truncate(now));
        };
        Instant end = to == null || to.isBlank() ? defaultTo : parseInstant("to", to);
        Instant defaultFrom = switch (unit) {
            case DAY -> BillingPeriod.current(clock).startInstant();
            case HOUR -> end.minus(Duration.ofHours(24));
        };
        Instant start = from == null || from.isBlank() ? defaultFrom : parseInstant("from", from);
        if (!start.isBefore(end)) {
            throw invalid("from", "must be before to");
        }
        if (Duration.between(start, end).compareTo(unit.maxRange()) > 0) {
            throw invalid("to", "range must not exceed " + unit.maxRange().toDays() + " days for "
                    + unit.label() + " granularity");
        }
        return new TimeseriesQuery(resource, unit, start, end);
    }

    /** The start of every bucket overlapping {@code [from, to)}, in order. */
    public List<Instant> bucketStarts() {
        List<Instant> starts = new ArrayList<>();
        for (Instant bucket = granularity.truncate(from); bucket.isBefore(to); bucket = granularity.next(bucket)) {
            starts.add(bucket);
        }
        return starts;
    }

    private static UUID parseResource(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("resource_id", "is required");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw invalid("resource_id", "must be a UUID");
        }
    }

    private static Granularity parseGranularity(String value) {
        if (value == null || value.isBlank()) {
            return Granularity.DAY;
        }
        for (Granularity candidate : Granularity.values()) {
            if (candidate.label().equalsIgnoreCase(value.trim())) {
                return candidate;
            }
        }
        throw invalid("granularity", "must be hour or day");
    }

    private static Instant parseInstant(String field, String value) {
        String text = value.trim();
        try {
            return Instant.parse(text);
        } catch (DateTimeException notAnInstant) {
            try {
                return LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (DateTimeException notADate) {
                throw invalid(field, "must be an ISO-8601 instant or date");
            }
        }
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }
}
