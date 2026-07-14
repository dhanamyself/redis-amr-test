package com.example.amrkpi.report;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

@Component
public class CsvReportWriter {

    public byte[] write(ReportData data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PrintWriter w = new PrintWriter(out, true, StandardCharsets.UTF_8)) {
            w.println("AMR KPI Test Harness Report");
            row(w, "Generated At", java.time.Instant.now().toString());
            row(w, "Run ID", data.runId());
            row(w, "Run Type", data.runType());
            row(w, "Run Status", data.runStatus());
            row(w, "Window From", String.valueOf(data.from()));
            row(w, "Window To", String.valueOf(data.to()));
            w.println();

            w.println("Run Configuration");
            row(w, "configJson", data.runConfigJson());
            w.println();

            w.println("Circuit Breaker States");
            w.println("region,state");
            data.breakerStates().forEach((region, state) -> w.println(csv(region) + "," + csv(state)));
            w.println();

            w.println("Uptime");
            uptimeSection(w, data.uptimeLocal());
            uptimeSection(w, data.uptimeFailover());
            w.println();

            w.println("Circuit Breaker Time-In-State");
            breakerSection(w, data.breakerTimeInStateLocal());
            breakerSection(w, data.breakerTimeInStateFailover());
            w.println();

            w.println("Geo-Replication Lag (ms)");
            w.println("direction,count,min,p50,p95,p99,max,timeouts");
            geoRow(w, data.geoLocalToFailover());
            geoRow(w, data.geoFailoverToLocal());
            w.println();

            w.println("Network Time (us)");
            w.println("region,count,achievedOpsPerSec,min,p50,p95,p99,p999,max,errorTotal");
            statsRow(w, "local", data.networkTimeLocal());
            statsRow(w, "failover", data.networkTimeFailover());
            w.println();

            if (data.throughput() != null) {
                w.println("Throughput (KPI 3) — run " + data.runId());
                row(w, "targetOpsPerSec", String.valueOf(data.throughput().targetOpsPerSec()));
                row(w, "achievedOpsPerSec", String.valueOf(data.throughput().achievedOpsPerSec()));
                w.println("scope,count,achievedOpsPerSec,min,p50,p95,p99,p999,max,errorTotal");
                statsRow(w, "overall", data.throughput().overall());
                data.throughput().byOperation().forEach((op, stats) -> statsRow(w, "op:" + op, stats));
                data.throughput().byRegion().forEach((region, stats) -> statsRow(w, "region:" + region, stats));
                w.println();
            }

            w.println("Cache-Aside (KPI 6)");
            var ca = data.cacheAside();
            row(w, "hitCount", String.valueOf(ca.hitCount()));
            row(w, "missCount", String.valueOf(ca.missCount()));
            row(w, "degradedCount", String.valueOf(ca.degradedCount()));
            row(w, "missRatioPercent", String.valueOf(ca.missRatio()));
            w.println("scope,count,achievedOpsPerSec,min,p50,p95,p99,p999,max,errorTotal");
            statsRow(w, "hit", ca.hitLatency());
            statsRow(w, "miss", ca.missLatency());
            statsRow(w, "degraded", ca.degradedLatency());
            w.println();

            w.println("Consistency & Conflict (KPI 9)");
            var c = data.consistency();
            row(w, "stalenessProbeCount", String.valueOf(c.stalenessProbeCount()));
            row(w, "staleAtT0Count", String.valueOf(c.staleAtT0Count()));
            row(w, "staleWindowMillis(p50/p95/p99/max)", c.staleWindowMillis().p50() + "/" + c.staleWindowMillis().p95()
                    + "/" + c.staleWindowMillis().p99() + "/" + c.staleWindowMillis().max());
            row(w, "conflictProbeCount", String.valueOf(c.conflictProbeCount()));
            row(w, "convergedCount", String.valueOf(c.convergedCount()));
            row(w, "divergedCount", String.valueOf(c.divergedCount()));
            row(w, "convergenceTimeMillis(p50/p95/p99/max)", c.convergenceTimeMillis().p50() + "/" + c.convergenceTimeMillis().p95()
                    + "/" + c.convergenceTimeMillis().p99() + "/" + c.convergenceTimeMillis().max());
            w.println();

            w.println("Token Lifecycle (KPI 8)");
            var tl = data.tokenLifecycle();
            row(w, "successCount", String.valueOf(tl.successCount()));
            row(w, "failureCount", String.valueOf(tl.failureCount()));
            w.println("renewedAt,outcome,user,expiresAt,ttlMillis");
            tl.renewals().forEach(r -> w.println(String.join(",",
                    csv(String.valueOf(r.renewedAt())), csv(r.outcome()), csv(r.user()),
                    csv(String.valueOf(r.tokenExpiresAt())), csv(String.valueOf(r.tokenTtlMillis())))));
        }
        return out.toByteArray();
    }

    private void row(PrintWriter w, String key, String value) {
        w.println(csv(key) + "," + csv(value));
    }

    private void uptimeSection(PrintWriter w, com.example.amrkpi.kpi.uptime.UptimeReport report) {
        row(w, "region:" + report.region() + " uptimePercent", String.valueOf(report.uptimePercent()));
        row(w, "region:" + report.region() + " outageCount", String.valueOf(report.outages().size()));
    }

    private void breakerSection(PrintWriter w, com.example.amrkpi.kpi.circuitbreaker.BreakerTimeInStateReport report) {
        row(w, "region:" + report.region() + " currentState", report.currentState());
        report.timeInStateMillis().forEach((state, ms) -> row(w, "region:" + report.region() + " timeInState:" + state + "Millis", String.valueOf(ms)));
    }

    private void geoRow(PrintWriter w, com.example.amrkpi.kpi.geolatency.GeoReplicationReport report) {
        var s = report.latencyMillis();
        w.println(String.join(",", csv(report.direction()), String.valueOf(s.count()), String.valueOf(s.min()),
                String.valueOf(s.p50()), String.valueOf(s.p95()), String.valueOf(s.p99()), String.valueOf(s.max()),
                String.valueOf(report.timeoutCount())));
    }

    private void statsRow(PrintWriter w, String label, AggregateStats s) {
        if (s == null) {
            w.println(csv(label) + ",0,0,0,0,0,0,0,0,0");
            return;
        }
        w.println(String.join(",", csv(label), String.valueOf(s.count()), String.valueOf(s.achievedOpsPerSec()),
                String.valueOf(s.minMicros()), String.valueOf(s.p50Micros()), String.valueOf(s.p95Micros()),
                String.valueOf(s.p99Micros()), String.valueOf(s.p999Micros()), String.valueOf(s.maxMicros()),
                String.valueOf(s.errorTotal())));
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"").replace("\n", " ");
        return "\"" + escaped + "\"";
    }
}
