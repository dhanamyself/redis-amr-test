package com.example.amrkpi.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Central high-frequency latency/error capture point. Two parallel tracks, per the Metrics
 * architecture spec:
 *
 * <ul>
 *   <li>HdrHistogram {@link Recorder}s keyed by (runId, region, operation) — the source of
 *   truth for persisted per-interval rollups (see RollupSnapshotTask). Run-scoped so per-run
 *   reports are exact.</li>
 *   <li>Micrometer timers/counters tagged ONLY by {region, operation, outcome} (no runId — that
 *   would be unbounded cardinality) — an always-on live cross-check surfaced at
 *   /actuator/metrics, per the spec's explicit tag set.</li>
 * </ul>
 *
 * Latency is tracked in microseconds: highest trackable value 60s, 3 significant digits.
 */
@Component
public class OperationRecorder {

    private static final long HIGHEST_TRACKABLE_MICROS = Duration.ofSeconds(60).toNanos() / 1000;
    private static final int SIGNIFICANT_DIGITS = 3;

    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<Key, Recorder> histograms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, EnumMap<ErrorCategory, LongAdder>> errorCounts = new ConcurrentHashMap<>();
    private final Set<String> endedRuns = ConcurrentHashMap.newKeySet();

    public OperationRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public record Key(String runId, String region, String operation) {
    }

    public void recordSuccess(String runId, String region, String operation, long durationNanos) {
        Key key = new Key(runId, region, operation);
        histograms.computeIfAbsent(key, k -> newRecorder())
                .recordValue(Math.min(durationNanos / 1000, HIGHEST_TRACKABLE_MICROS));
        errorCounts.computeIfAbsent(key, k -> newErrorMap());

        Timer.builder("amr.kpi.operation.latency")
                .tag("region", region)
                .tag("operation", operation)
                .tag("outcome", "success")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Duration.ofNanos(durationNanos));
    }

    public void recordError(String runId, String region, String operation, ErrorCategory category) {
        Key key = new Key(runId, region, operation);
        histograms.computeIfAbsent(key, k -> newRecorder());
        errorCounts.computeIfAbsent(key, k -> newErrorMap())
                .get(category)
                .increment();

        Counter.builder("amr.kpi.operation.errors")
                .tag("region", region)
                .tag("operation", operation)
                .tag("outcome", "error")
                .tag("errorCategory", category.name())
                .register(meterRegistry)
                .increment();
    }

    public void markRunEnded(String runId) {
        endedRuns.add(runId);
    }

    /** Called by the rollup snapshotter each interval. */
    public Set<Key> activeKeys() {
        return Set.copyOf(histograms.keySet());
    }

    public Histogram takeIntervalHistogram(Key key) {
        Recorder r = histograms.get(key);
        return r == null ? null : r.getIntervalHistogram();
    }

    public Map<ErrorCategory, Long> takeAndResetErrorCounts(Key key) {
        EnumMap<ErrorCategory, LongAdder> map = errorCounts.get(key);
        if (map == null) {
            return Map.of();
        }
        EnumMap<ErrorCategory, Long> snapshot = new EnumMap<>(ErrorCategory.class);
        map.forEach((cat, adder) -> {
            long v = adder.sumThenReset();
            if (v > 0) {
                snapshot.put(cat, v);
            }
        });
        return snapshot;
    }

    /** Drop the histogram/error-count entries for a key once its run has ended and it's drained. */
    public void cleanupIfDrained(Key key, long lastSnapshotCount) {
        if (endedRuns.contains(key.runId()) && lastSnapshotCount == 0) {
            histograms.remove(key);
            errorCounts.remove(key);
        }
    }

    private Recorder newRecorder() {
        return new Recorder(HIGHEST_TRACKABLE_MICROS, SIGNIFICANT_DIGITS);
    }

    private EnumMap<ErrorCategory, LongAdder> newErrorMap() {
        EnumMap<ErrorCategory, LongAdder> map = new EnumMap<>(ErrorCategory.class);
        for (ErrorCategory c : ErrorCategory.values()) {
            map.put(c, new LongAdder());
        }
        return map;
    }
}
