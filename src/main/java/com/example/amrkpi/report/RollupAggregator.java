package com.example.amrkpi.report;

import com.example.amrkpi.persistence.entity.MetricRollup;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one shared query-layer utility every KPI/report/export that reports on rollups goes
 * through, per the build spec's "never duplicate aggregation logic per format" rule. Excludes
 * warm-up rollups from percentile/throughput math by default, matching "warm-up excluded from
 * reported aggregates (still visible on charts)".
 */
@Component
public class RollupAggregator {

    private final ObjectMapper objectMapper;

    public RollupAggregator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AggregateStats aggregate(List<MetricRollup> rollups) {
        return aggregate(rollups, true);
    }

    public AggregateStats aggregate(List<MetricRollup> rollups, boolean excludeWarmUp) {
        List<MetricRollup> effective = rollups.stream()
                .filter(r -> !excludeWarmUp || !r.isWarmUp())
                .toList();

        if (effective.isEmpty()) {
            return new AggregateStats(0, null, null, 0, 0, 0, 0, 0, 0, 0, Map.of(), 0);
        }

        long count = 0;
        long min = Long.MAX_VALUE;
        long max = 0;
        double p50Weighted = 0, p95Weighted = 0, p99Weighted = 0, p999Weighted = 0;
        Instant windowStart = null;
        Instant windowEnd = null;
        Map<String, Long> errorCounts = new LinkedHashMap<>();
        long errorTotal = 0;

        for (MetricRollup r : effective) {
            if (windowStart == null || r.getWindowStart().isBefore(windowStart)) {
                windowStart = r.getWindowStart();
            }
            if (windowEnd == null || r.getWindowEnd().isAfter(windowEnd)) {
                windowEnd = r.getWindowEnd();
            }
            long c = r.getSuccessCount();
            if (c > 0) {
                count += c;
                min = Math.min(min, r.getMinMicros());
                max = Math.max(max, r.getMaxMicros());
                p50Weighted += r.getP50Micros() * (double) c;
                p95Weighted += r.getP95Micros() * (double) c;
                p99Weighted += r.getP99Micros() * (double) c;
                p999Weighted += r.getP999Micros() * (double) c;
            }
            Map<String, Long> windowErrors = readErrorCounts(r.getErrorCountsJson());
            for (Map.Entry<String, Long> e : windowErrors.entrySet()) {
                errorCounts.merge(e.getKey(), e.getValue(), Long::sum);
                errorTotal += e.getValue();
            }
        }

        double durationSeconds = (windowStart != null && windowEnd != null)
                ? Math.max(Duration.between(windowStart, windowEnd).toMillis() / 1000.0, 0.001)
                : 1.0;

        return new AggregateStats(
                count,
                windowStart,
                windowEnd,
                count / durationSeconds,
                count > 0 ? min : 0,
                count > 0 ? Math.round(p50Weighted / count) : 0,
                count > 0 ? Math.round(p95Weighted / count) : 0,
                count > 0 ? Math.round(p99Weighted / count) : 0,
                count > 0 ? Math.round(p999Weighted / count) : 0,
                count > 0 ? max : 0,
                errorCounts,
                errorTotal
        );
    }

    private Map<String, Long> readErrorCounts(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Long>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
