package com.meterly.billing;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PricingEngineTest {
    private final PricingEngine engine = new PricingEngine();
    private PricingEngine.Plan starter() {
        return new PricingEngine.Plan(UUID.randomUUID(), "STARTER", "Starter", new BigDecimal("19.00"), 10000, "USD", List.of(
                new PricingEngine.Tier(10000, 100000L, new BigDecimal("0.002000")),
                new PricingEngine.Tier(100000, null, new BigDecimal("0.001500"))));
    }
    @Test void workedExampleAndPerResourceAllowance() {
        assertThat(engine.price(starter(), List.of(new PricingEngine.ResourceUsage(UUID.randomUUID(), "Calls", 150000))).total())
                .isEqualByComparingTo("274.00");
        assertThat(engine.price(starter(), List.of(new PricingEngine.ResourceUsage(UUID.randomUUID(), "Calls", 10000),
                new PricingEngine.ResourceUsage(UUID.randomUUID(), "Jobs", 10000))).total()).isEqualByComparingTo("19.00");
    }
    @Test void exactTierBoundaries() {
        for (long units : new long[]{0, 9999, 10000, 10001, 99999, 100000, 100001, 150003}) {
            var quote = engine.price(starter(), List.of(new PricingEngine.ResourceUsage(UUID.randomUUID(), "Calls", units)));
            assertThat(quote.lines().stream().skip(1).mapToLong(PricingEngine.Line::quantity).sum()).isEqualTo(units);
        }
    }
    @Test void halfEvenRoundsOnlyAfterMultiplication() {
        var plan = new PricingEngine.Plan(UUID.randomUUID(), "TEST", "Test", BigDecimal.ZERO, 0, "USD",
                List.of(new PricingEngine.Tier(0, null, new BigDecimal("0.005000"))));
        assertThat(engine.price(plan, List.of(new PricingEngine.ResourceUsage(UUID.randomUUID(), "Calls", 1))).total()).isEqualByComparingTo("0.00");
        assertThat(engine.price(plan, List.of(new PricingEngine.ResourceUsage(UUID.randomUUID(), "Calls", 3))).total()).isEqualByComparingTo("0.02");
    }
    @Test void rejectsGapsInsteadOfSilentlyUnderbilling() {
        var bad = new PricingEngine.Plan(UUID.randomUUID(), "BAD", "Bad", BigDecimal.ZERO, 0, "USD",
                List.of(new PricingEngine.Tier(10, null, BigDecimal.ONE)));
        assertThatThrownBy(() -> engine.price(bad, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
