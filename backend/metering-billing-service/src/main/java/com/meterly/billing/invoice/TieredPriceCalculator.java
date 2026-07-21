package com.meterly.billing.invoice;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class TieredPriceCalculator {
    private static final int CENTS = 2;

    public BillingAmounts calculate(
            BigDecimal basePrice,
            long includedUnits,
            BigDecimal marginalUnitPrice,
            long totalUsage
    ) {
        if (basePrice == null || marginalUnitPrice == null) {
            throw new IllegalArgumentException("Prices are required");
        }
        if (includedUnits < 0 || totalUsage < 0 || basePrice.signum() < 0 || marginalUnitPrice.signum() < 0) {
            throw new IllegalArgumentException("Usage and prices cannot be negative");
        }

        long overageUnits = Math.max(0, totalUsage - includedUnits);
        BigDecimal baseAmount = basePrice.setScale(CENTS, RoundingMode.HALF_EVEN);
        BigDecimal overageAmount = marginalUnitPrice
                .multiply(BigDecimal.valueOf(overageUnits))
                .setScale(CENTS, RoundingMode.HALF_EVEN);
        return new BillingAmounts(
                baseAmount,
                overageUnits,
                marginalUnitPrice,
                overageAmount,
                baseAmount.add(overageAmount).setScale(CENTS, RoundingMode.HALF_EVEN)
        );
    }

    public record BillingAmounts(
            BigDecimal baseAmount,
            long overageUnits,
            BigDecimal marginalUnitPrice,
            BigDecimal overageAmount,
            BigDecimal total
    ) {
    }
}

