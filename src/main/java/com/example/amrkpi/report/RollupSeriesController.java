package com.example.amrkpi.report;

import com.example.amrkpi.persistence.entity.MetricRollup;
import com.example.amrkpi.persistence.repository.MetricRollupRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Raw rollup time series for dashboard charts (network time, throughput). This is a passthrough
 * of individual rollup rows, not an aggregation — it doesn't duplicate the aggregation logic in
 * {@link RollupAggregator}, it just lists the raw points a chart needs to plot over time.
 */
@RestController
public class RollupSeriesController {

    private final MetricRollupRepository rollupRepository;
    private final ObjectMapper objectMapper;

    public RollupSeriesController(MetricRollupRepository rollupRepository, ObjectMapper objectMapper) {
        this.rollupRepository = rollupRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Lists individual rollup points for one operation, for charting. Scope by either
     * {@code runId} (all rollups for that run, ignoring the time window) or {@code region} +
     * {@code from}/{@code to}; with neither, returns all regions in the window.
     *
     * @param operation the rollup operation key (e.g. {@code loadgen.get}, {@code network.ping})
     * @param region    optional region filter
     * @param runId     optional run ID filter — takes precedence over the time window when set
     * @param from      window start (ignored if {@code runId} is set); defaults to 10 minutes before {@code to}
     * @param to        window end (ignored if {@code runId} is set); defaults to now
     * @return one point per rollup window: count, p50/p95/p99, and total error count
     */
    @GetMapping("/report/series")
    public List<RollupPoint> series(
            @RequestParam String operation,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(10, ChronoUnit.MINUTES);

        List<MetricRollup> rollups;
        if (runId != null) {
            rollups = rollupRepository.findByRunIdAndOperationOrderByWindowStartAsc(runId, operation);
        } else if (region != null) {
            rollups = rollupRepository.findByOperationAndRegionAndWindowStartBetweenOrderByWindowStartAsc(
                    operation, region, effectiveFrom, effectiveTo);
        } else {
            rollups = rollupRepository.findByOperationAndWindowStartBetweenOrderByWindowStartAsc(
                    operation, effectiveFrom, effectiveTo);
        }

        return rollups.stream()
                .filter(r -> region == null || region.equals(r.getRegion()))
                .filter(r -> !r.getWindowStart().isBefore(effectiveFrom) && !r.getWindowStart().isAfter(effectiveTo))
                .map(r -> new RollupPoint(r.getWindowStart(), r.getSuccessCount(), r.getP50Micros(), r.getP95Micros(),
                        r.getP99Micros(), errorTotal(r.getErrorCountsJson())))
                .toList();
    }

    private long errorTotal(String json) {
        if (json == null || json.isBlank()) {
            return 0;
        }
        try {
            Map<String, Long> errors = objectMapper.readValue(json, new TypeReference<Map<String, Long>>() {
            });
            return errors.values().stream().mapToLong(Long::longValue).sum();
        } catch (Exception e) {
            return 0;
        }
    }
}
