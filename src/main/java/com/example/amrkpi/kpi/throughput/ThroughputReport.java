package com.example.amrkpi.kpi.throughput;

import com.example.amrkpi.loadgen.LoadGenConfig;
import com.example.amrkpi.report.AggregateStats;

import java.util.Map;

public record ThroughputReport(
        String runId,
        LoadGenConfig config,
        boolean warmUpExcluded,
        AggregateStats overall,
        Map<String, AggregateStats> byOperation,
        Map<String, AggregateStats> byRegion,
        double targetOpsPerSec,
        double achievedOpsPerSec
) {
}
