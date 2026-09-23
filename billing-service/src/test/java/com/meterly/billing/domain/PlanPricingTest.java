package com.meterly.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A plan configuration that could under- or over-bill is rejected before anything is priced. */
class PlanPricingTest {

    private static PlanPricing plan(String basePrice, long includedUnits, Tier... tiers) {
        return new PlanPricing("TEST", "Test", new BigDecimal(basePrice), includedUnits, "USD", List.of(tiers));
    }

    @Test
    void acceptsTheCatalogShapeAndNormalizesScales() {
        PlanPricing plan = plan("19", 10_000, Tier.openEnded(100_000, "0.0015"), Tier.bounded(10_000, 100_000, "0.002"));

        assertThat(plan.basePrice()).isEqualTo("19.00");
        assertThat(plan.tiers()).extracting(Tier::fromUnit).containsExactly(10_000L, 100_000L);
        assertThat(plan.tiers()).extracting(Tier::unitPrice)
                .containsExactly(new BigDecimal("0.002000"), new BigDecimal("0.001500"));
    }

    @Test
    void rejectsAPlanWithoutTiers() {
        assertThatThrownBy(() -> plan("19.00", 10_000)).isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("no pricing tiers");
    }

    @Test
    void rejectsAFirstTierThatDoesNotStartAtTheAllowance() {
        assertThatThrownBy(() -> plan("19.00", 10_000, Tier.openEnded(20_000, "0.002")))
                .isInstanceOf(InvalidPlanException.class).hasMessageContaining("first tier must start at included_units");
        assertThatThrownBy(() -> plan("19.00", 10_000, Tier.openEnded(0, "0.002")))
                .isInstanceOf(InvalidPlanException.class);
    }

    @Test
    void rejectsGapsAndOverlapsBetweenTiers() {
        assertThatThrownBy(() -> plan("0", 0, Tier.bounded(0, 100, "0.01"), Tier.openEnded(150, "0.005")))
                .isInstanceOf(InvalidPlanException.class).hasMessageContaining("contiguous");
        assertThatThrownBy(() -> plan("0", 0, Tier.bounded(0, 100, "0.01"), Tier.openEnded(50, "0.005")))
                .isInstanceOf(InvalidPlanException.class).hasMessageContaining("contiguous");
    }

    @Test
    void requiresExactlyOneOpenEndedTierAtTheEnd() {
        assertThatThrownBy(() -> plan("0", 0, Tier.bounded(0, 100, "0.01")))
                .isInstanceOf(InvalidPlanException.class).hasMessageContaining("last tier must be open-ended");
        assertThatThrownBy(() -> plan("0", 0, Tier.openEnded(0, "0.01"), Tier.openEnded(100, "0.005")))
                .isInstanceOf(InvalidPlanException.class).hasMessageContaining("only the last tier");
    }

    @Test
    void rejectsNegativeValues() {
        assertThatThrownBy(() -> plan("-1.00", 0, Tier.openEnded(0, "0.01"))).isInstanceOf(InvalidPlanException.class);
        assertThatThrownBy(() -> plan("0", -1, Tier.openEnded(-1, "0.01"))).isInstanceOf(InvalidPlanException.class);
        assertThatThrownBy(() -> Tier.openEnded(0, "-0.000001")).isInstanceOf(InvalidPlanException.class);
        assertThatThrownBy(() -> Tier.openEnded(-5, "0.01")).isInstanceOf(InvalidPlanException.class);
    }

    @Test
    void rejectsEmptyOrInvertedTierRanges() {
        assertThatThrownBy(() -> Tier.bounded(100, 100, "0.01")).isInstanceOf(InvalidPlanException.class);
        assertThatThrownBy(() -> Tier.bounded(100, 50, "0.01")).isInstanceOf(InvalidPlanException.class);
    }

    @Test
    void rejectsPricesMorePreciseThanTheirColumnsInsteadOfRounding() {
        assertThatThrownBy(() -> Tier.openEnded(0, "0.0000001")).isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("more than 6 decimal places");
        assertThatThrownBy(() -> plan("19.001", 0, Tier.openEnded(0, "0.01"))).isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("more than 2 decimal places");
        // Trailing zeros are not extra precision.
        assertThat(Tier.openEnded(0, "0.00200000000").unitPrice()).isEqualTo("0.002000");
    }

    @Test
    void rejectsMissingIdentityAndInvalidCurrency() {
        List<Tier> tiers = List.of(Tier.openEnded(0, "0.01"));
        assertThatThrownBy(() -> new PlanPricing(" ", "Test", BigDecimal.ONE, 0, "USD", tiers))
                .isInstanceOf(InvalidPlanException.class);
        assertThatThrownBy(() -> new PlanPricing("TEST", "Test", BigDecimal.ONE, 0, "usd", tiers))
                .isInstanceOf(InvalidPlanException.class);
        assertThatThrownBy(() -> new PlanPricing("TEST", "Test", null, 0, "USD", tiers))
                .isInstanceOf(InvalidPlanException.class);
    }

    @Test
    void rejectsNegativeUsage() {
        assertThatThrownBy(() -> new ResourceUsage(UUID.randomUUID(), "API calls", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void draftRefusesATotalThatDoesNotAddUp() {
        LineItem fee = LineItem.priced(1, "fee", null, 1, new BigDecimal("19.00"));

        assertThatThrownBy(() -> new InvoiceDraft("TEST", "Test", "USD", List.of(fee), new BigDecimal("19.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new InvoiceDraft("TEST", "Test", "USD", List.of(fee), new BigDecimal("19.00")).totalAmount())
                .isEqualTo("19.00");
    }
}
