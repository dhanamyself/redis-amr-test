package com.example.amrkpi.report;

import java.time.Instant;
import java.util.Map;

/**
 * Aggregate view over a set of {@link com.example.amrkpi.persistence.entity.MetricRollup} rows.
 * Percentiles are a weighted-average approximation across per-interval rollups (min/max are
 * exact — min-of-mins, max-of-maxes); true cross-window percentile precision would require
 * persisting raw histograms, which the Metrics architecture spec explicitly rules out at
 * session-store intensity. Good enough for trend reporting; said explicitly in the README.
 */
public record AggregateStats(
        long count,
        Instant windowStart,
        Instant windowEnd,
        double achievedOpsPerSec,
        long minMicros,
        long p50Micros,
        long p95Micros,
        long p99Micros,
        long p999Micros,
        long maxMicros,
        Map<String, Long> errorCounts,
        long errorTotal
) {
}
