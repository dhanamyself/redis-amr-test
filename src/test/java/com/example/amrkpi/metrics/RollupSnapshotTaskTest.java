package com.example.amrkpi.metrics;

import com.example.amrkpi.persistence.entity.MetricRollup;
import com.example.amrkpi.persistence.repository.MetricRollupRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Wires a real OperationRecorder (real HdrHistogram) into RollupSnapshotTask to verify the whole
 * "in-memory histogram -> persisted rollup row" path end to end, including the two properties
 * every downstream report depends on: percentiles actually come out ordered
 * (min <= p50 <= p95 <= p99 <= p999 <= max), and error-only windows (no successes at all) still
 * produce a row instead of being silently dropped.
 */
@ExtendWith(MockitoExtension.class)
class RollupSnapshotTaskTest {

    @Mock
    private MetricRollupRepository repository;
    @Mock
    private RunService runService;

    private OperationRecorder recorder;
    private RollupSnapshotTask task;

    @BeforeEach
    void setUp() {
        recorder = new OperationRecorder(new SimpleMeterRegistry());
        task = new RollupSnapshotTask(recorder, repository, runService, new ObjectMapper());
    }

    @Test
    void snapshotsASuccessOnlyWindowWithOrderedPercentiles() {
        for (int i = 1; i <= 100; i++) {
            recorder.recordSuccess("run-1", "canada-central", "loadgen.get", i * 100_000L); // 0.1ms..10ms
        }
        when(runService.isWithinWarmUp(eq("run-1"), any())).thenReturn(false);

        task.snapshot();

        ArgumentCaptor<MetricRollup> captor = ArgumentCaptor.forClass(MetricRollup.class);
        verify(repository).save(captor.capture());
        MetricRollup rollup = captor.getValue();

        assertThat(rollup.getRunId()).isEqualTo("run-1");
        assertThat(rollup.getRegion()).isEqualTo("canada-central");
        assertThat(rollup.getOperation()).isEqualTo("loadgen.get");
        assertThat(rollup.getSuccessCount()).isEqualTo(100);
        assertThat(rollup.isWarmUp()).isFalse();
        assertThat(rollup.getMinMicros()).isLessThanOrEqualTo(rollup.getP50Micros());
        assertThat(rollup.getP50Micros()).isLessThanOrEqualTo(rollup.getP95Micros());
        assertThat(rollup.getP95Micros()).isLessThanOrEqualTo(rollup.getP99Micros());
        assertThat(rollup.getP99Micros()).isLessThanOrEqualTo(rollup.getP999Micros());
        assertThat(rollup.getP999Micros()).isLessThanOrEqualTo(rollup.getMaxMicros());
    }

    @Test
    void snapshotsAnErrorOnlyWindowInsteadOfDroppingIt() {
        recorder.recordError("run-1", "canada-central", "loadgen.set", ErrorCategory.CIRCUIT_BREAKER_REJECTION);
        recorder.recordError("run-1", "canada-central", "loadgen.set", ErrorCategory.CIRCUIT_BREAKER_REJECTION);
        when(runService.isWithinWarmUp(eq("run-1"), any())).thenReturn(false);

        task.snapshot();

        ArgumentCaptor<MetricRollup> captor = ArgumentCaptor.forClass(MetricRollup.class);
        verify(repository).save(captor.capture());
        MetricRollup rollup = captor.getValue();

        assertThat(rollup.getSuccessCount()).isZero();
        assertThat(rollup.getErrorCountsJson()).contains("CIRCUIT_BREAKER_REJECTION").contains("2");
    }

    @Test
    void doesNotPersistAnythingWhenNoOperationsWereRecorded() {
        task.snapshot();

        verify(repository, never()).save(any());
    }

    @Test
    void marksRollupsWarmUpAccordingToRunService() {
        recorder.recordSuccess("run-1", "canada-central", "loadgen.get", 1_000_000);
        when(runService.isWithinWarmUp(eq("run-1"), any())).thenReturn(true);

        task.snapshot();

        ArgumentCaptor<MetricRollup> captor = ArgumentCaptor.forClass(MetricRollup.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isWarmUp()).isTrue();
    }

    @Test
    void drainedKeysForEndedRunsAreClearedAfterASecondEmptySnapshot() {
        recorder.recordSuccess("run-done", "canada-central", "loadgen.get", 1_000_000);
        when(runService.isWithinWarmUp(eq("run-done"), any())).thenReturn(false);

        task.snapshot(); // first snapshot persists the recorded op and drains the histogram
        recorder.markRunEnded("run-done");
        task.snapshot(); // second snapshot sees an empty, ended-run key and should purge it

        assertThat(recorder.activeKeys()).isEmpty();
        verify(repository, times(1)).save(any()); // only the first snapshot had anything to persist
    }

    @Test
    void windowBoundariesAdvanceBetweenConsecutiveSnapshots() {
        recorder.recordSuccess("run-1", "canada-central", "loadgen.get", 1_000_000);
        when(runService.isWithinWarmUp(eq("run-1"), any())).thenReturn(false);
        task.snapshot();

        ArgumentCaptor<MetricRollup> first = ArgumentCaptor.forClass(MetricRollup.class);
        verify(repository).save(first.capture());
        Instant firstWindowEnd = first.getValue().getWindowEnd();

        recorder.recordSuccess("run-1", "canada-central", "loadgen.get", 1_000_000);
        task.snapshot();

        List<MetricRollup> all = new java.util.ArrayList<>();
        ArgumentCaptor<MetricRollup> both = ArgumentCaptor.forClass(MetricRollup.class);
        verify(repository, times(2)).save(both.capture());
        all.addAll(both.getAllValues());

        MetricRollup second = all.get(1);
        assertThat(second.getWindowStart()).isEqualTo(firstWindowEnd);
        assertThat(second.getWindowEnd()).isAfterOrEqualTo(second.getWindowStart());
    }
}
