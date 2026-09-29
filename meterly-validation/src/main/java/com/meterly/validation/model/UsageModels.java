package com.meterly.validation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response shapes from metering-service (docs/api-contract.md §3). */
public final class UsageModels {
    private UsageModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UsagePlanRef(String code, String name, long includedUnits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UsageItem(UUID resourceId, String resourceName, String unitLabel, long totalUnits,
                             long includedUnits, double percentOfIncluded) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UsageCurrentResponse(LocalDate periodStart, LocalDate periodEnd, UsagePlanRef plan,
                                        List<UsageItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimeseriesPoint(Instant bucketStart, long units) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimeseriesResponse(UUID resourceId, String granularity, Instant from, Instant to,
                                      List<TimeseriesPoint> points) {
    }
}
