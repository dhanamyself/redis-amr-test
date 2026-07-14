package com.example.amrkpi.report;

import com.example.amrkpi.persistence.entity.MetricRollup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RollupAggregator's weighted-average percentile math and warm-up filtering feed every KPI
 * report and export in the app — a mistake here would quietly skew every latency number the
 * harness reports, so the arithmetic is worth pinning down exactly rather than trusting it by
 * inspection.
 */
class RollupAggregatorTest {

    private final RollupAggregator aggregator = new RollupAggregator(new ObjectMapper());

    private MetricRollup rollup(Instant start, Instant end, boolean warmUp, long count,
                                 long min, long p50, long p95, long p99, long p999, long max, String errorJson) {
        MetricRollup r = new MetricRollup();
        r.setRunId("run-1");
        r.setRegion("canada-central");
        r.setOperation("loadgen.get");
        r.setWindowStart(start);
        r.setWindowEnd(end);
        r.setWarmUp(warmUp);
        r.setSuccessCount(count);
        r.setMinMicros(min);
        r.setP50Micros(p50);
        r.setP95Micros(p95);
        r.setP99Micros(p99);
        r.setP999Micros(p999);
        r.setMaxMicros(max);
        r.setErrorCountsJson(errorJson);
        return r;
    }

    @Test
    void emptyInputReturnsZeroedStats() {
        AggregateStats stats = aggregator.aggregate(List.of());

        assertThat(stats.count()).isZero();
        assertThat(stats.windowStart()).isNull();
        assertThat(stats.windowEnd()).isNull();
        assertThat(stats.errorCounts()).isEmpty();
        assertThat(stats.errorTotal()).isZero();
    }

    @Test
    void computesCountWeightedAveragePercentilesAcrossWindows() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        List<MetricRollup> rollups = List.of(
                rollup(t0, t0.plusSeconds(1), false, 100, 10, 50, 90, 99, 150, 200, "{\"SOCKET_READ_TIMEOUT\":2}"),
                rollup(t0.plusSeconds(1), t0.plusSeconds(2), false, 300, 5, 60, 95, 100, 250, 300,
                        "{\"SOCKET_READ_TIMEOUT\":1,\"AUTH_FAILURE\":4}")
        );

        AggregateStats stats = aggregator.aggregate(rollups);

        assertThat(stats.count()).isEqualTo(400);
        assertThat(stats.minMicros()).isEqualTo(5);
        assertThat(stats.maxMicros()).isEqualTo(300);
        // (50*100 + 60*300) / 400 = 57.5 -> rounds to 58
        assertThat(stats.p50Micros()).isEqualTo(58);
        // (90*100 + 95*300) / 400 = 93.75 -> rounds to 94
        assertThat(stats.p95Micros()).isEqualTo(94);
        // (99*100 + 100*300) / 400 = 99.75 -> rounds to 100
        assertThat(stats.p99Micros()).isEqualTo(100);
        assertThat(stats.errorCounts()).containsEntry("SOCKET_READ_TIMEOUT", 3L).containsEntry("AUTH_FAILURE", 4L);
        assertThat(stats.errorTotal()).isEqualTo(7);
        assertThat(stats.windowStart()).isEqualTo(t0);
        assertThat(stats.windowEnd()).isEqualTo(t0.plusSeconds(2));
        assertThat(stats.achievedOpsPerSec()).isEqualTo(200.0);
    }

    @Test
    void excludesWarmUpRollupsByDefault() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        List<MetricRollup> rollups = List.of(
                rollup(t0, t0.plusSeconds(1), true, 10_000, 1, 1, 1, 1, 1, 1, null),
                rollup(t0.plusSeconds(1), t0.plusSeconds(2), false, 100, 10, 50, 90, 99, 150, 200, null)
        );

        AggregateStats withWarmUpExcluded = aggregator.aggregate(rollups);
        assertThat(withWarmUpExcluded.count()).isEqualTo(100);

        AggregateStats withWarmUpIncluded = aggregator.aggregate(rollups, false);
        assertThat(withWarmUpIncluded.count()).isEqualTo(10_100);
    }

    @Test
    void errorOnlyRollupsWithNoSuccessesAreCountedInErrorsButNotLatency() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        List<MetricRollup> rollups = List.of(
                rollup(t0, t0.plusSeconds(1), false, 0, 0, 0, 0, 0, 0, 0, "{\"CIRCUIT_BREAKER_REJECTION\":5}")
        );

        AggregateStats stats = aggregator.aggregate(rollups);

        assertThat(stats.count()).isZero();
        assertThat(stats.errorTotal()).isEqualTo(5);
        assertThat(stats.errorCounts()).containsEntry("CIRCUIT_BREAKER_REJECTION", 5L);
    }

    @Test
    void malformedErrorJsonIsIgnoredRatherThanThrowing() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        List<MetricRollup> rollups = List.of(
                rollup(t0, t0.plusSeconds(1), false, 50, 1, 2, 3, 4, 5, 6, "{not-valid-json")
        );

        AggregateStats stats = aggregator.aggregate(rollups);

        assertThat(stats.count()).isEqualTo(50);
        assertThat(stats.errorCounts()).isEmpty();
    }
}
