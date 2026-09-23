package com.meterly.validation.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Nearest-rank percentile math over a set of millisecond latencies (no external stats library). */
public final class Percentiles {

    public record Summary(long p50, long p95, long p99, long max, int count) {
        public boolean p95Under(long thresholdMillis) {
            return count > 0 && p95 < thresholdMillis;
        }
    }

    private Percentiles() {
    }

    public static Summary of(List<Long> latenciesMillis) {
        if (latenciesMillis.isEmpty()) {
            return new Summary(0, 0, 0, 0, 0);
        }
        List<Long> sorted = new ArrayList<>(latenciesMillis);
        Collections.sort(sorted);
        return new Summary(percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99),
                sorted.getLast(), sorted.size());
    }

    /** Nearest-rank: the smallest value such that at least {@code p} percent of samples are ≤ it. */
    static long percentile(List<Long> sorted, double p) {
        int n = sorted.size();
        int rank = (int) Math.ceil(p / 100.0 * n);
        rank = Math.max(1, Math.min(n, rank));
        return sorted.get(rank - 1);
    }

    /** How many of the first {@code n} samples (in the order they were recorded) are under a threshold. */
    public static int countUnder(List<Long> latenciesInOrder, int firstN, long thresholdMillis) {
        int limit = Math.min(firstN, latenciesInOrder.size());
        int count = 0;
        for (int i = 0; i < limit; i++) {
            if (latenciesInOrder.get(i) < thresholdMillis) {
                count++;
            }
        }
        return count;
    }
}
