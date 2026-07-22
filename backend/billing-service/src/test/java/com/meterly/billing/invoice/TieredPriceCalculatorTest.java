package com.meterly.billing.invoice;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TieredPriceCalculatorTest {
    private final TieredPriceCalculator calculator = new TieredPriceCalculator();

    @Test
    void pricesAcrossMultipleTiersWithHalfEvenMoneyRounding() {
        var result = calculator.calculate(new BigDecimal("99.00"), 5000, List.of(
                new TieredPriceCalculator.PricingTier(5001, 10000L, new BigDecimal("0.015000")),
                new TieredPriceCalculator.PricingTier(10001, null, new BigDecimal("0.010000"))
        ), 12000);
        assertThat(result.total()).isEqualByComparingTo("194.00");
        assertThat(result.tierCharges()).extracting(TieredPriceCalculator.TierCharge::quantity).containsExactly(5000L, 2000L);
    }

    @Test
    void includesOnlyBaseWhenUsageDoesNotExceedIncludedUnits() {
        var result = calculator.calculate(new BigDecimal("49"), 1000, List.of(
                new TieredPriceCalculator.PricingTier(1001, null, new BigDecimal("0.020000"))
        ), 1000);
        assertThat(result.total()).isEqualByComparingTo("49.00");
        assertThat(result.tierCharges()).isEmpty();
    }
}
