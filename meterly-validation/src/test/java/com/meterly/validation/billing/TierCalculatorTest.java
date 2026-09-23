package com.meterly.validation.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterly.validation.model.BillingModels.Plan;
import com.meterly.validation.model.BillingModels.PricingTier;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Cross-checks {@link TierCalculator} against the demo seed's hand-calculated figures
 * (meterly-db/src/main/resources/db/demo/R__demo_seed.sql) and PDD §12's boundary cases: usage exactly
 * at included_units, usage spanning two tiers, and zero usage.
 */
class TierCalculatorTest {

    private static final List<PricingTier> STARTER_TIERS = List.of(
            new PricingTier(10_000, 100_000L, new BigDecimal("0.002000")),
            new PricingTier(100_000, null, new BigDecimal("0.001500")));

    private static final List<PricingTier> GROWTH_TIERS = List.of(
            new PricingTier(100_000, null, new BigDecimal("0.001000")));

    @Test
    void acmeInvoiceMatchesTheHandCalculatedDemoFigure() {
        Plan starter = new Plan(UUID.randomUUID(), "STARTER", "Starter", new BigDecimal("19.00"), 10_000, "USD", STARTER_TIERS);
        Map<String, Long> usage = Map.of("api-calls", 150_000L, "webhooks", 12_345L);

        assertThat(TierCalculator.invoiceTotal(starter, usage)).isEqualByComparingTo("278.69");
    }

    @Test
    void globexInvoiceMatchesTheHandCalculatedDemoFigure() {
        Plan growth = new Plan(UUID.randomUUID(), "GROWTH", "Growth", new BigDecimal("99.00"), 100_000, "USD", GROWTH_TIERS);
        Map<String, Long> usage = Map.of("api-calls", 250_000L);

        assertThat(TierCalculator.invoiceTotal(growth, usage)).isEqualByComparingTo("249.00");
    }

    @Test
    void usageExactlyAtIncludedUnitsIsFree() {
        assertThat(TierCalculator.chargeForResource(10_000, STARTER_TIERS)).isEqualByComparingTo("0.00");
    }

    @Test
    void zeroUsageIsFree() {
        assertThat(TierCalculator.chargeForResource(0, STARTER_TIERS)).isEqualByComparingTo("0.00");
    }

    @Test
    void oneUnitOverTheIncludedAllowanceEntersTheFirstTierButRoundsToZeroAtTheCent() {
        // 1 unit * 0.002000 = 0.002 -> HALF_EVEN to 2dp -> 0.00 (the line item is rounded, not the total).
        assertThat(TierCalculator.chargeForResource(10_001, STARTER_TIERS)).isEqualByComparingTo("0.00");
    }

    @Test
    void enoughOverageUnitsToClearTheCentRoundingThreshold() {
        // 5 units * 0.002000 = 0.010 -> HALF_EVEN to 2dp -> 0.01
        assertThat(TierCalculator.chargeForResource(10_005, STARTER_TIERS)).isEqualByComparingTo("0.01");
    }

    @Test
    void usageSpanningTwoTiersChargesEachTierSeparately() {
        // 10,000 included + 90,000 @ 0.002 + 1 @ 0.0015
        BigDecimal charge = TierCalculator.chargeForResource(100_001, STARTER_TIERS);
        assertThat(charge).isEqualByComparingTo(new BigDecimal("0.002000").multiply(BigDecimal.valueOf(90_000))
                .setScale(2, java.math.RoundingMode.HALF_EVEN)
                .add(new BigDecimal("0.001500").setScale(2, java.math.RoundingMode.HALF_EVEN)));
    }

    @Test
    void unboundedFinalTierHasNoUpperLimit() {
        assertThat(TierCalculator.chargeForResource(10_000_000, GROWTH_TIERS))
                .isEqualByComparingTo(new BigDecimal("0.001000").multiply(BigDecimal.valueOf(9_900_000))
                        .setScale(2, java.math.RoundingMode.HALF_EVEN));
    }

    @Test
    void eachTierLineIsRoundedIndependentlyBeforeSumming() {
        // A unit price and quantity chosen so unrounded-then-summed vs rounded-per-tier-then-summed would
        // disagree if the calculator rounded only once at the end (HALF_EVEN "banker's rounding" at 0.005).
        List<PricingTier> tiers = List.of(
                new PricingTier(0, 3L, new BigDecimal("0.005")),
                new PricingTier(3, null, new BigDecimal("0.015")));
        // Tier 1: 3 * 0.005 = 0.015 -> HALF_EVEN -> 0.02 ; Tier 2: 1 * 0.015 = 0.015 -> HALF_EVEN -> 0.02
        assertThat(TierCalculator.chargeForResource(4, tiers)).isEqualByComparingTo("0.04");
    }
}
