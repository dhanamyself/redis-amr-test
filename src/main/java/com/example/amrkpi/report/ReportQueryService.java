package com.example.amrkpi.report;

import com.example.amrkpi.kpi.cachemiss.CacheAsideService;
import com.example.amrkpi.kpi.circuitbreaker.BreakerService;
import com.example.amrkpi.kpi.consistency.ConsistencyService;
import com.example.amrkpi.kpi.geolatency.GeoReplicationService;
import com.example.amrkpi.kpi.networktime.NetworkTimeProbeScheduler;
import com.example.amrkpi.kpi.throughput.ThroughputService;
import com.example.amrkpi.kpi.tokenlifecycle.TokenLifecycleService;
import com.example.amrkpi.kpi.uptime.UptimeService;
import com.example.amrkpi.metrics.RunService;
import com.example.amrkpi.persistence.repository.MetricRollupRepository;
import com.example.amrkpi.redis.AmrEndpoints;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class ReportQueryService {

    private final AmrEndpoints endpoints;
    private final UptimeService uptimeService;
    private final BreakerService breakerService;
    private final GeoReplicationService geoReplicationService;
    private final MetricRollupRepository rollupRepository;
    private final RollupAggregator aggregator;
    private final ThroughputService throughputService;
    private final CacheAsideService cacheAsideService;
    private final ConsistencyService consistencyService;
    private final TokenLifecycleService tokenLifecycleService;
    private final RunService runService;

    public ReportQueryService(AmrEndpoints endpoints, UptimeService uptimeService, BreakerService breakerService,
                               GeoReplicationService geoReplicationService, MetricRollupRepository rollupRepository,
                               RollupAggregator aggregator, ThroughputService throughputService,
                               CacheAsideService cacheAsideService, ConsistencyService consistencyService,
                               TokenLifecycleService tokenLifecycleService, RunService runService) {
        this.endpoints = endpoints;
        this.uptimeService = uptimeService;
        this.breakerService = breakerService;
        this.geoReplicationService = geoReplicationService;
        this.rollupRepository = rollupRepository;
        this.aggregator = aggregator;
        this.throughputService = throughputService;
        this.cacheAsideService = cacheAsideService;
        this.consistencyService = consistencyService;
        this.tokenLifecycleService = tokenLifecycleService;
        this.runService = runService;
    }

    public ReportData build(String runId, Instant from, Instant to) {
        String runType = null;
        String runStatus = null;
        String runConfigJson = null;
        if (runId != null) {
            var run = runService.get(runId).orElse(null);
            if (run != null) {
                runType = run.getType();
                runStatus = run.getStatus().name();
                runConfigJson = run.getConfigJson();
            }
        }

        String localName = endpoints.localName();
        String failoverName = endpoints.failoverName();

        var networkTimeLocal = aggregator.aggregate(
                rollupRepository.findByOperationAndRegionAndWindowStartBetweenOrderByWindowStartAsc(
                        NetworkTimeProbeScheduler.OPERATION, localName, from, to), false);
        var networkTimeFailover = aggregator.aggregate(
                rollupRepository.findByOperationAndRegionAndWindowStartBetweenOrderByWindowStartAsc(
                        NetworkTimeProbeScheduler.OPERATION, failoverName, from, to), false);

        return new ReportData(
                runId, runType, runStatus, runConfigJson, from, to,
                breakerService.currentStates(),
                uptimeService.report(localName, from, to),
                uptimeService.report(failoverName, from, to),
                breakerService.report(localName, from, to),
                breakerService.report(failoverName, from, to),
                geoReplicationService.report(geoReplicationService.direction(localName, failoverName), from, to),
                geoReplicationService.report(geoReplicationService.direction(failoverName, localName), from, to),
                networkTimeLocal,
                networkTimeFailover,
                runId != null ? throughputService.report(runId) : null,
                cacheAsideService.report(from, to),
                consistencyService.report(from, to),
                tokenLifecycleService.report(runId, from, to, null)
        );
    }
}
