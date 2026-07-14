package com.example.amrkpi.report;

import java.util.List;

/** Simple percentile computation over an in-memory sample list — used by the low-frequency KPIs
 * (geo-replication lag, consistency convergence) whose raw samples are persisted individually
 * and small enough in volume not to need histogram rollups. */
public final class PercentileUtil {

    private PercentileUtil() {
    }

    public record Stats(long count, long min, long p50, long p95, long p99, long max) {
        public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, 0);
    }

    public static Stats stats(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return Stats.EMPTY;
        }
        List<Long> sorted = values.stream().sorted().toList();
        return new Stats(
                sorted.size(),
                sorted.get(0),
                percentile(sorted, 50),
                percentile(sorted, 95),
                percentile(sorted, 99),
                sorted.get(sorted.size() - 1)
        );
    }

    private static long percentile(List<Long> sorted, double p) {
        int index = (int) Math.ceil((p / 100.0) * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }
}
