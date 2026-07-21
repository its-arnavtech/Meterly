package com.meterly.billing.invoice;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TieredPriceCalculatorTest {
    private final TieredPriceCalculator calculator = new TieredPriceCalculator();

    @Test
    void handCalculatedInvoiceMatchesToTheCent() {
        TieredPriceCalculator.BillingAmounts result = calculator.calculate(
                new BigDecimal("49.00"), 1000, new BigDecimal("0.020000"), 1375);

        assertThat(result.overageUnits()).isEqualTo(375);
        assertThat(result.overageAmount()).isEqualByComparingTo("7.50");
        assertThat(result.total()).isEqualByComparingTo("56.50");
    }

    @Test
    void includedBoundaryAndZeroUsageChargeOnlyBasePrice() {
        assertThat(calculator.calculate(
                new BigDecimal("49.00"), 1000, new BigDecimal("0.020000"), 0).total())
                .isEqualByComparingTo("49.00");
        assertThat(calculator.calculate(
                new BigDecimal("49.00"), 1000, new BigDecimal("0.020000"), 1000).total())
                .isEqualByComparingTo("49.00");
    }

    @Test
    void roundsOnceAtFinalLineAmountUsingHalfEven() {
        TieredPriceCalculator.BillingAmounts result = calculator.calculate(
                new BigDecimal("0.00"), 0, new BigDecimal("0.005"), 1);

        assertThat(result.overageAmount()).isEqualByComparingTo("0.00");
        assertThat(result.total()).isEqualByComparingTo("0.00");
    }
}

