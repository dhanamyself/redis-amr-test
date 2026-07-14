package com.example.amrkpi.report;

import com.example.amrkpi.kpi.cachemiss.CacheAsideReport;
import com.example.amrkpi.kpi.circuitbreaker.BreakerTimeInStateReport;
import com.example.amrkpi.kpi.consistency.ConsistencyReport;
import com.example.amrkpi.kpi.geolatency.GeoReplicationReport;
import com.example.amrkpi.kpi.throughput.ThroughputReport;
import com.example.amrkpi.kpi.tokenlifecycle.TokenLifecycleReport;
import com.example.amrkpi.kpi.uptime.UptimeReport;

import java.time.Instant;
import java.util.Map;

/**
 * The one shared query-layer result every export format (CSV/XLSX/PDF) and the dashboard summary
 * are built from — per the build spec's "one shared query layer" rule for /reports/export.
 * throughput/tokenLifecycleCorrelation are null when no runId was supplied, since those are
 * inherently per-run.
 */
public record ReportData(
        String runId,
        String runType,
        String runStatus,
        String runConfigJson,
        Instant from,
        Instant to,
        Map<String, String> breakerStates,
        UptimeReport uptimeLocal,
        UptimeReport uptimeFailover,
        BreakerTimeInStateReport breakerTimeInStateLocal,
        BreakerTimeInStateReport breakerTimeInStateFailover,
        GeoReplicationReport geoLocalToFailover,
        GeoReplicationReport geoFailoverToLocal,
        AggregateStats networkTimeLocal,
        AggregateStats networkTimeFailover,
        ThroughputReport throughput,
        CacheAsideReport cacheAside,
        ConsistencyReport consistency,
        TokenLifecycleReport tokenLifecycle
) {
}
