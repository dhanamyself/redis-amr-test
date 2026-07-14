package com.example.amrkpi.kpi.cachemiss;

import com.example.amrkpi.report.AggregateStats;

import java.time.Instant;

public record CacheAsideReport(
        Instant from,
        Instant to,
        long hitCount,
        long missCount,
        long degradedCount,
        double missRatio,
        AggregateStats hitLatency,
        AggregateStats missLatency,
        AggregateStats degradedLatency
) {
}
