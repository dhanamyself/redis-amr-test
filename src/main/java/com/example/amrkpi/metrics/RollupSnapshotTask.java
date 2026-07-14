package com.example.amrkpi.metrics;

import com.example.amrkpi.persistence.entity.MetricRollup;
import com.example.amrkpi.persistence.repository.MetricRollupRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Snapshots every active (runId, region, operation) HdrHistogram on a fixed interval (default
 * 1s), persists the rollup, and resets/rotates the histogram. This is what keeps the harness
 * from bottlenecking on its own instrumentation at session-store intensity — see the Metrics
 * architecture section of the build spec.
 */
@Component
public class RollupSnapshotTask {

    private static final Logger log = LoggerFactory.getLogger(RollupSnapshotTask.class);

    private final OperationRecorder recorder;
    private final MetricRollupRepository repository;
    private final RunService runService;
    private final ObjectMapper objectMapper;

    private volatile Instant lastWindowEnd = Instant.now();

    public RollupSnapshotTask(OperationRecorder recorder, MetricRollupRepository repository,
                               RunService runService, ObjectMapper objectMapper) {
        this.recorder = recorder;
        this.repository = repository;
        this.runService = runService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedRateString = "#{${amrkpi.metrics.rollup-interval-seconds} * 1000}")
    public void snapshot() {
        Instant windowEnd = Instant.now();
        Instant windowStart = lastWindowEnd;
        lastWindowEnd = windowEnd;

        for (OperationRecorder.Key key : recorder.activeKeys()) {
            try {
                snapshotOne(key, windowStart, windowEnd);
            } catch (Exception e) {
                log.warn("Failed to snapshot rollup for {}: {}", key, e.getMessage(), e);
            }
        }
    }

    private void snapshotOne(OperationRecorder.Key key, Instant windowStart, Instant windowEnd) {
        Histogram histogram = recorder.takeIntervalHistogram(key);
        Map<ErrorCategory, Long> errors = recorder.takeAndResetErrorCounts(key);
        long errorTotal = errors.values().stream().mapToLong(Long::longValue).sum();
        long successCount = histogram == null ? 0 : histogram.getTotalCount();

        if (successCount == 0 && errorTotal == 0) {
            recorder.cleanupIfDrained(key, 0);
            return;
        }

        MetricRollup rollup = new MetricRollup();
        rollup.setRunId(key.runId());
        rollup.setRegion(key.region());
        rollup.setOperation(key.operation());
        rollup.setWindowStart(windowStart);
        rollup.setWindowEnd(windowEnd);
        rollup.setWarmUp(runService.isWithinWarmUp(key.runId(), windowEnd));
        rollup.setSuccessCount(successCount);
        if (successCount > 0) {
            rollup.setMinMicros(histogram.getMinValue());
            rollup.setP50Micros(histogram.getValueAtPercentile(50.0));
            rollup.setP95Micros(histogram.getValueAtPercentile(95.0));
            rollup.setP99Micros(histogram.getValueAtPercentile(99.0));
            rollup.setP999Micros(histogram.getValueAtPercentile(99.9));
            rollup.setMaxMicros(histogram.getMaxValue());
        }
        rollup.setErrorCountsJson(writeJson(errors));
        repository.save(rollup);

        recorder.cleanupIfDrained(key, successCount + errorTotal);
    }

    private String writeJson(Map<ErrorCategory, Long> errors) {
        if (errors.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(errors);
        } catch (Exception e) {
            return null;
        }
    }
}
