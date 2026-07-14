package com.example.amrkpi.kpi.networktime;

import com.example.amrkpi.persistence.repository.MetricRollupRepository;
import com.example.amrkpi.report.AggregateStats;
import com.example.amrkpi.report.RollupAggregator;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * REST surface for KPI 7 (network time). Reports round-trip PING latency to one region, isolated
 * from command processing, from rollups produced by {@link NetworkTimeProbeScheduler} — a
 * high-frequency probe (default every 200ms) run on a dedicated connection, never through the
 * load generator's pool (see the probe-isolation note in {@code ProbeConnectionFactory}).
 */
@RestController
public class NetworkTimeController {

    private final MetricRollupRepository rollupRepository;
    private final RollupAggregator aggregator;

    public NetworkTimeController(MetricRollupRepository rollupRepository, RollupAggregator aggregator) {
        this.rollupRepository = rollupRepository;
        this.aggregator = aggregator;
    }

    /**
     * Aggregates network-time rollups for one region over a window (full percentile distribution
     * through p99.9, achieved samples/sec, error breakdown).
     *
     * @param region the region name (e.g. {@code canada-central})
     * @param from   window start; defaults to 15 minutes before {@code to}
     * @param to     window end; defaults to now
     * @return aggregated round-trip latency stats for the region
     */
    @GetMapping("/kpi/network-time")
    public AggregateStats networkTime(
            @RequestParam String region,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(15, ChronoUnit.MINUTES);
        var rollups = rollupRepository.findByOperationAndRegionAndWindowStartBetweenOrderByWindowStartAsc(
                NetworkTimeProbeScheduler.OPERATION, region, effectiveFrom, effectiveTo);
        return aggregator.aggregate(rollups, false);
    }
}
