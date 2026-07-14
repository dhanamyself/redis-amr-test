package com.example.amrkpi.report;

import java.time.Instant;

/** Lightweight passthrough of a single rollup row — feeds dashboard charts. Not an aggregation,
 * so it doesn't run afoul of the "never duplicate aggregation logic per format" rule. */
public record RollupPoint(Instant windowStart, long count, long p50Micros, long p95Micros, long p99Micros, long errorCount) {
}
