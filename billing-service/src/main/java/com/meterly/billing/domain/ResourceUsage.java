package com.meterly.billing.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Total units one metered resource consumed in a billing period (summed from the usage ledger).
 *
 * @param resourceId   the metered resource
 * @param resourceName display name used in line-item descriptions
 * @param totalUnits   non-negative total for the period
 */
public record ResourceUsage(UUID resourceId, String resourceName, long totalUnits) {

    public ResourceUsage {
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(resourceName, "resourceName");
        if (totalUnits < 0) {
            throw new IllegalArgumentException("Usage of resource " + resourceId + " must not be negative: " + totalUnits);
        }
    }
}
