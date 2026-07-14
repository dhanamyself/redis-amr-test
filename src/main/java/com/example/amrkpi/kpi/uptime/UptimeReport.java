package com.example.amrkpi.kpi.uptime;

import java.time.Instant;
import java.util.List;

public record UptimeReport(
        String region,
        Instant from,
        Instant to,
        double uptimePercent,
        long probeCount,
        List<Outage> outages
) {
    public record Outage(Instant start, Instant end, long durationMillis, String errorCategory) {
    }
}
