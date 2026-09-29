package com.meterly.validation.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class PercentilesTest {

    @Test
    void oneToOneHundredGivesExactNearestRankPercentiles() {
        List<Long> values = LongStream.rangeClosed(1, 100).boxed().toList();
        List<Long> shuffled = new ArrayList<>(values);
        Collections.shuffle(shuffled, new java.util.Random(7));

        Percentiles.Summary summary = Percentiles.of(shuffled);

        assertThat(summary.p50()).isEqualTo(50);
        assertThat(summary.p95()).isEqualTo(95);
        assertThat(summary.p99()).isEqualTo(99);
        assertThat(summary.max()).isEqualTo(100);
        assertThat(summary.count()).isEqualTo(100);
    }

    @Test
    void emptyInputYieldsAZeroedSummaryNotAnException() {
        Percentiles.Summary summary = Percentiles.of(List.of());

        assertThat(summary.count()).isZero();
        assertThat(summary.p95Under(850)).isFalse();
    }

    @Test
    void singleValueIsEveryPercentile() {
        Percentiles.Summary summary = Percentiles.of(List.of(42L));

        assertThat(summary.p50()).isEqualTo(42);
        assertThat(summary.p95()).isEqualTo(42);
        assertThat(summary.p99()).isEqualTo(42);
        assertThat(summary.max()).isEqualTo(42);
    }

    @Test
    void p95UnderRespectsTheThresholdStrictly() {
        Percentiles.Summary exactly850 = Percentiles.of(List.of(850L));
        Percentiles.Summary under850 = Percentiles.of(List.of(849L));

        assertThat(exactly850.p95Under(850)).isFalse();
        assertThat(under850.p95Under(850)).isTrue();
    }

    @Test
    void countUnderChecksTheFirstNInRecordedOrderNotSortedOrder() {
        // First 20 recorded: eighteen fast trials then two slow ones (order matters for the POC bar).
        List<Long> recorded = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            recorded.add(100L);
        }
        recorded.add(5000L);
        recorded.add(5000L);
        recorded.add(50L); // a 21st, fast sample that must not count toward "first 20"

        assertThat(Percentiles.countUnder(recorded, 20, 2000)).isEqualTo(18);
    }
}
