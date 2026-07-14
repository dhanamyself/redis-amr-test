package com.example.amrkpi.kpi.consistency;

import com.example.amrkpi.report.PercentileUtil;

import java.time.Instant;

public record ConsistencyReport(
        Instant from,
        Instant to,
        long stalenessProbeCount,
        long staleAtT0Count,
        PercentileUtil.Stats staleWindowMillis,
        long conflictProbeCount,
        long convergedCount,
        long divergedCount,
        PercentileUtil.Stats convergenceTimeMillis
) {
}
