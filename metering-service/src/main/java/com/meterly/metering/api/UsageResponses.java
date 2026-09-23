package com.meterly.metering.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * JSON bodies of the {@code /v1/usage} endpoints (snake_case via the shared ObjectMapper). Shapes are
 * fixed by {@code docs/api-contract.md} section 3.
 */
public final class UsageResponses {

    private UsageResponses() {
    }

    /** {@code GET /v1/usage/current}. */
    public record CurrentUsage(LocalDate periodStart, LocalDate periodEnd, Plan plan, List<Item> items) {

        public CurrentUsage {
            items = List.copyOf(items);
        }
    }

    public record Plan(String code, String name, long includedUnits) {
    }

    /** One active resource; {@code percentOfIncluded} is a JSON number rounded to one decimal. */
    public record Item(UUID resourceId, String resourceName, String unitLabel, long totalUnits, long includedUnits,
                       double percentOfIncluded) {
    }

    /** {@code GET /v1/usage/timeseries}. */
    public record Timeseries(UUID resourceId, String granularity, Instant from, Instant to, List<Point> points) {

        public Timeseries {
            points = List.copyOf(points);
        }
    }

    public record Point(Instant bucketStart, long units) {
    }
}
