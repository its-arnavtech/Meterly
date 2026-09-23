package com.meterly.metering.threshold;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The threshold comparison stays exact for values far beyond what a {@code long} product can hold. */
class ThresholdEvaluatorTest {

    @Test
    void reachesAThresholdOnlyAtOrAboveItsShareOfTheAllowance() {
        assertThat(ThresholdEvaluator.reached(7_999, 10_000, 80)).isFalse();
        assertThat(ThresholdEvaluator.reached(8_000, 10_000, 80)).isTrue();
        assertThat(ThresholdEvaluator.reached(9_999, 10_000, 100)).isFalse();
        assertThat(ThresholdEvaluator.reached(10_000, 10_000, 100)).isTrue();
        assertThat(ThresholdEvaluator.reached(10_001, 10_000, 100)).isTrue();
    }

    @Test
    void roundsUpFractionalThresholds() {
        // 80% of 3 units is 2.4: only 3 units reach it.
        assertThat(ThresholdEvaluator.reached(2, 3, 80)).isFalse();
        assertThat(ThresholdEvaluator.reached(3, 3, 80)).isTrue();
    }

    @Test
    void doesNotOverflowOnHugeCounters() {
        assertThat(ThresholdEvaluator.reached(Long.MAX_VALUE, Long.MAX_VALUE, 100)).isTrue();
        assertThat(ThresholdEvaluator.reached(Long.MAX_VALUE - 1, Long.MAX_VALUE, 100)).isFalse();
    }
}
