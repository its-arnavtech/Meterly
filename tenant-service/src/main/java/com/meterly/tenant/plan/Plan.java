package com.meterly.tenant.plan;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Contract "Plan" object. Money is {@link BigDecimal} (serialized as a plain decimal string). A tier prices
 * cumulative units in {@code (from_unit, to_unit]}; the last tier has {@code to_unit = null}.
 */
public record Plan(
        UUID id,
        String code,
        String name,
        BigDecimal basePrice,
        long includedUnits,
        String currency,
        List<Tier> tiers) {

    public Plan {
        tiers = List.copyOf(tiers);
    }

    public record Tier(long fromUnit, Long toUnit, BigDecimal unitPrice) {
    }
}
