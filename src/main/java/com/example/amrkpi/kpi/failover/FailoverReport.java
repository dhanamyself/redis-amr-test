package com.example.amrkpi.kpi.failover;

import com.example.amrkpi.report.AggregateStats;

import java.time.Instant;

public record FailoverReport(
        String region,
        Instant inducedAt,
        Instant failoverDetectedAt,
        Long failoverDurationMillis,
        Instant restoredAt,
        Instant failbackDetectedAt,
        Long failbackDurationMillis,
        AggregateStats duringFailoverTransition,
        AggregateStats duringFailbackTransition
) {
}
