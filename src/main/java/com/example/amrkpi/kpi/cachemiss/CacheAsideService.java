package com.example.amrkpi.kpi.cachemiss;

import com.example.amrkpi.config.AmrKpiProperties;
import com.example.amrkpi.metrics.ErrorCategory;
import com.example.amrkpi.metrics.OperationRecorder;
import com.example.amrkpi.persistence.entity.Run;
import com.example.amrkpi.persistence.repository.MetricRollupRepository;
import com.example.amrkpi.redis.AmrEndpoints;
import com.example.amrkpi.report.RollupAggregator;
import org.springframework.stereotype.Service;
import redis.clients.jedis.MultiDbClient;
import redis.clients.jedis.params.SetParams;

import java.time.Instant;

/**
 * KPI 6 — a real cache-aside path (not a simulation): GET Redis, on miss fetch from the H2-backed
 * source-of-truth stand-in, populate with TTL, return. When Redis itself is unavailable (breaker
 * open / connection failure), falls back straight to the source of truth — this is also the
 * degraded-mode demonstration Microsoft recommends: the report's "cacheaside.degraded" latency
 * is the price of running cache-less.
 */
@Service
public class CacheAsideService {

    public static final String OP_HIT = "cacheaside.hit";
    public static final String OP_MISS = "cacheaside.miss";
    public static final String OP_DEGRADED = "cacheaside.degraded";

    private final MultiDbClient redisClient;
    private final SourceOfTruthService sourceOfTruthService;
    private final OperationRecorder recorder;
    private final AmrEndpoints endpoints;
    private final MetricRollupRepository rollupRepository;
    private final RollupAggregator aggregator;
    private final int ttlSeconds;

    public CacheAsideService(MultiDbClient redisClient, SourceOfTruthService sourceOfTruthService,
                              OperationRecorder recorder, AmrEndpoints endpoints, AmrKpiProperties props,
                              MetricRollupRepository rollupRepository, RollupAggregator aggregator) {
        this.redisClient = redisClient;
        this.sourceOfTruthService = sourceOfTruthService;
        this.recorder = recorder;
        this.endpoints = endpoints;
        this.rollupRepository = rollupRepository;
        this.aggregator = aggregator;
        this.ttlSeconds = props.getCacheAside().getTtlSeconds();
    }

    public String get(String key, String runId) {
        String effectiveRunId = runId != null ? runId : Run.BACKGROUND_RUN_ID;
        long start = System.nanoTime();
        try {
            String value = redisClient.get(key);
            String region = currentRegion();
            if (value != null) {
                recorder.recordSuccess(effectiveRunId, region, OP_HIT, System.nanoTime() - start);
                return value;
            }
            String fromSource = sourceOfTruthService.fetch(key);
            redisClient.set(key, fromSource, SetParams.setParams().ex(ttlSeconds));
            recorder.recordSuccess(effectiveRunId, region, OP_MISS, System.nanoTime() - start);
            return fromSource;
        } catch (Exception e) {
            ErrorCategory category = ErrorCategory.classify(e);
            recorder.recordError(effectiveRunId, currentRegion(), OP_DEGRADED, category);
            String fromSource = sourceOfTruthService.fetch(key);
            recorder.recordSuccess(effectiveRunId, currentRegion(), OP_DEGRADED, System.nanoTime() - start);
            return fromSource;
        }
    }

    private String currentRegion() {
        try {
            return endpoints.nameOf(redisClient.getActiveDatabaseEndpoint());
        } catch (Exception e) {
            return "n/a";
        }
    }

    public CacheAsideReport report(Instant from, Instant to) {
        var hitRollups = rollupRepository.findByOperationAndWindowStartBetweenOrderByWindowStartAsc(OP_HIT, from, to);
        var missRollups = rollupRepository.findByOperationAndWindowStartBetweenOrderByWindowStartAsc(OP_MISS, from, to);
        var degradedRollups = rollupRepository.findByOperationAndWindowStartBetweenOrderByWindowStartAsc(OP_DEGRADED, from, to);

        var hitStats = aggregator.aggregate(hitRollups, false);
        var missStats = aggregator.aggregate(missRollups, false);
        var degradedStats = aggregator.aggregate(degradedRollups, false);

        long hitCount = hitStats.count();
        long missCount = missStats.count();
        long degradedCount = degradedStats.count();
        long total = hitCount + missCount + degradedCount;
        double missRatio = total == 0 ? 0.0 : (missCount + degradedCount) * 100.0 / total;

        return new CacheAsideReport(from, to, hitCount, missCount, degradedCount, missRatio,
                hitStats, missStats, degradedStats);
    }
}
