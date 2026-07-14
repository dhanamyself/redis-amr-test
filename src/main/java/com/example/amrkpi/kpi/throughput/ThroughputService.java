package com.example.amrkpi.kpi.throughput;

import com.example.amrkpi.loadgen.LoadGenConfig;
import com.example.amrkpi.loadgen.SessionWorkloadGenerator;
import com.example.amrkpi.metrics.RunService;
import com.example.amrkpi.persistence.entity.MetricRollup;
import com.example.amrkpi.persistence.entity.Run;
import com.example.amrkpi.persistence.repository.MetricRollupRepository;
import com.example.amrkpi.report.RollupAggregator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * KPI 3 report: achieved vs target ops/sec, latency percentiles through p99.9, and error
 * breakdown — split by op type and region, warm-up excluded, built entirely from rollups (per
 * Metrics architecture: no raw per-op samples exist to fall back to at this intensity).
 */
@Service
public class ThroughputService {

    private final MetricRollupRepository rollupRepository;
    private final RollupAggregator aggregator;
    private final RunService runService;
    private final ObjectMapper objectMapper;

    public ThroughputService(MetricRollupRepository rollupRepository, RollupAggregator aggregator,
                              RunService runService, ObjectMapper objectMapper) {
        this.rollupRepository = rollupRepository;
        this.aggregator = aggregator;
        this.runService = runService;
        this.objectMapper = objectMapper;
    }

    public ThroughputReport report(String runId) {
        Run run = runService.get(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown runId: " + runId));
        LoadGenConfig config = readConfig(run.getConfigJson());

        List<MetricRollup> rollups = rollupRepository.findByRunIdOrderByWindowStartAsc(runId).stream()
                .filter(r -> SessionWorkloadGenerator.OP_GET.equals(r.getOperation())
                        || SessionWorkloadGenerator.OP_SET.equals(r.getOperation()))
                .toList();

        var overall = aggregator.aggregate(rollups);

        Map<String, List<MetricRollup>> byOp = rollups.stream()
                .collect(Collectors.groupingBy(MetricRollup::getOperation));
        Map<String, List<MetricRollup>> byRegion = rollups.stream()
                .collect(Collectors.groupingBy(MetricRollup::getRegion));

        Map<String, com.example.amrkpi.report.AggregateStats> opStats = new LinkedHashMap<>();
        byOp.forEach((op, list) -> opStats.put(op, aggregator.aggregate(list)));

        Map<String, com.example.amrkpi.report.AggregateStats> regionStats = new LinkedHashMap<>();
        byRegion.forEach((region, list) -> regionStats.put(region, aggregator.aggregate(list)));

        return new ThroughputReport(
                runId,
                config,
                true,
                overall,
                opStats,
                regionStats,
                config != null ? config.targetOpsPerSec() : 0,
                overall.achievedOpsPerSec()
        );
    }

    private LoadGenConfig readConfig(String json) {
        try {
            return objectMapper.readValue(json, LoadGenConfig.class);
        } catch (Exception e) {
            return null;
        }
    }
}
