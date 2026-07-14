package com.example.amrkpi.kpi.geolatency;

import com.example.amrkpi.report.PercentileUtil;

import java.time.Instant;

public record GeoReplicationReport(
        String direction,
        Instant from,
        Instant to,
        PercentileUtil.Stats latencyMillis,
        long timeoutCount
) {
}
