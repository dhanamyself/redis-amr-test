package com.example.amrkpi.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * OperationRecorder is the single choke point every KPI's latency/error data flows through
 * before it's rolled up and persisted — a bug here (wrong reset semantics, a key that never gets
 * cleaned up, an error count that isn't isolated per key) would quietly corrupt every report in
 * the app without ever throwing, so it's worth exercising directly rather than only indirectly
 * through whatever happens to call it.
 */
class OperationRecorderTest {

    private OperationRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new OperationRecorder(new SimpleMeterRegistry());
    }

    @Test
    void recordedSuccessIsVisibleInTheIntervalHistogram() {
        OperationRecorder.Key key = new OperationRecorder.Key("run-1", "canada-central", "loadgen.get");

        recorder.recordSuccess("run-1", "canada-central", "loadgen.get", 5_000_000); // 5ms = 5000us

        Histogram histogram = recorder.takeIntervalHistogram(key);
        assertThat(histogram.getTotalCount()).isEqualTo(1);
        assertThat(histogram.getValueAtPercentile(50)).isCloseTo(5000, within(50L));
    }

    @Test
    void intervalHistogramResetsAfterEachSnapshot() {
        OperationRecorder.Key key = new OperationRecorder.Key("run-1", "canada-central", "loadgen.get");
        recorder.recordSuccess("run-1", "canada-central", "loadgen.get", 1_000_000);

        Histogram first = recorder.takeIntervalHistogram(key);
        assertThat(first.getTotalCount()).isEqualTo(1);

        // Nothing recorded since the last snapshot -> the next interval must be empty, not
        // cumulative. This is the exact property the "rollups instead of raw samples" design
        // depends on.
        Histogram second = recorder.takeIntervalHistogram(key);
        assertThat(second.getTotalCount()).isZero();

        recorder.recordSuccess("run-1", "canada-central", "loadgen.get", 2_000_000);
        Histogram third = recorder.takeIntervalHistogram(key);
        assertThat(third.getTotalCount()).isEqualTo(1);
    }

    @Test
    void errorCountsAreClassifiedPerCategoryAndResetOnRead() {
        OperationRecorder.Key key = new OperationRecorder.Key("run-1", "canada-central", "loadgen.set");

        recorder.recordError("run-1", "canada-central", "loadgen.set", ErrorCategory.SOCKET_READ_TIMEOUT);
        recorder.recordError("run-1", "canada-central", "loadgen.set", ErrorCategory.SOCKET_READ_TIMEOUT);
        recorder.recordError("run-1", "canada-central", "loadgen.set", ErrorCategory.AUTH_FAILURE);

        Map<ErrorCategory, Long> firstRead = recorder.takeAndResetErrorCounts(key);
        assertThat(firstRead).containsEntry(ErrorCategory.SOCKET_READ_TIMEOUT, 2L)
                .containsEntry(ErrorCategory.AUTH_FAILURE, 1L);
        // Categories with zero count in this window shouldn't appear at all.
        assertThat(firstRead).doesNotContainKey(ErrorCategory.POOL_EXHAUSTION);

        Map<ErrorCategory, Long> secondRead = recorder.takeAndResetErrorCounts(key);
        assertThat(secondRead).isEmpty();
    }

    @Test
    void recordErrorAloneMakesTheKeyDiscoverableWithAnEmptyHistogram() {
        OperationRecorder.Key key = new OperationRecorder.Key("run-1", "canada-central", "loadgen.set");

        recorder.recordError("run-1", "canada-central", "loadgen.set", ErrorCategory.CONNECT_TIMEOUT);

        assertThat(recorder.activeKeys()).contains(key);
        assertThat(recorder.takeIntervalHistogram(key).getTotalCount()).isZero();
    }

    @Test
    void distinctKeysAreTrackedIndependently() {
        recorder.recordSuccess("run-1", "canada-central", "loadgen.get", 1_000_000);
        recorder.recordSuccess("run-2", "canada-central", "loadgen.get", 9_000_000);
        recorder.recordError("run-1", "canada-east", "loadgen.get", ErrorCategory.OTHER);

        OperationRecorder.Key run1Local = new OperationRecorder.Key("run-1", "canada-central", "loadgen.get");
        OperationRecorder.Key run2Local = new OperationRecorder.Key("run-2", "canada-central", "loadgen.get");
        OperationRecorder.Key run1Failover = new OperationRecorder.Key("run-1", "canada-east", "loadgen.get");

        assertThat(recorder.activeKeys()).containsExactlyInAnyOrder(run1Local, run2Local, run1Failover);
        assertThat(recorder.takeIntervalHistogram(run1Local).getValueAtPercentile(50)).isCloseTo(1000, within(20L));
        assertThat(recorder.takeIntervalHistogram(run2Local).getValueAtPercentile(50)).isCloseTo(9000, within(100L));
        assertThat(recorder.takeAndResetErrorCounts(run1Failover)).containsEntry(ErrorCategory.OTHER, 1L);
        // run-1/canada-central was never given an error -> must not have picked one up.
        assertThat(recorder.takeAndResetErrorCounts(run1Local)).isEmpty();
    }

    @Test
    void cleanupOnlyRemovesKeysForRunsThatHaveEnded() {
        OperationRecorder.Key activeKey = new OperationRecorder.Key("run-active", "canada-central", "loadgen.get");
        OperationRecorder.Key endedKey = new OperationRecorder.Key("run-ended", "canada-central", "loadgen.get");
        recorder.recordSuccess("run-active", "canada-central", "loadgen.get", 1_000_000);
        recorder.recordSuccess("run-ended", "canada-central", "loadgen.get", 1_000_000);

        // Drain both histograms to zero first, as the real snapshotter does before deciding
        // whether to clean up.
        recorder.takeIntervalHistogram(activeKey);
        recorder.takeIntervalHistogram(endedKey);

        recorder.markRunEnded("run-ended");

        recorder.cleanupIfDrained(activeKey, 0);
        recorder.cleanupIfDrained(endedKey, 0);

        assertThat(recorder.activeKeys()).contains(activeKey);
        assertThat(recorder.activeKeys()).doesNotContain(endedKey);
    }

    @Test
    void cleanupDoesNotRemoveAnEndedRunsKeyIfItStillHasUndrainedData() {
        OperationRecorder.Key key = new OperationRecorder.Key("run-ended", "canada-central", "loadgen.get");
        recorder.recordSuccess("run-ended", "canada-central", "loadgen.get", 1_000_000);
        recorder.markRunEnded("run-ended");

        // lastSnapshotCount > 0 means this snapshot still had data -> must not be purged yet,
        // even though the run has ended, or the tail of a run's data would be lost.
        recorder.cleanupIfDrained(key, 1);

        assertThat(recorder.activeKeys()).contains(key);
    }
}
