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

@RestController
public class NetworkTimeController {

    private final MetricRollupRepository rollupRepository;
    private final RollupAggregator aggregator;

    public NetworkTimeController(MetricRollupRepository rollupRepository, RollupAggregator aggregator) {
        this.rollupRepository = rollupRepository;
        this.aggregator = aggregator;
    }

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
