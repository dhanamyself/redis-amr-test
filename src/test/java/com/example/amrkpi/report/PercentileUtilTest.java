package com.example.amrkpi.report;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PercentileUtilTest {

    @Test
    void emptyInputReturnsEmptyStats() {
        assertThat(PercentileUtil.stats(List.of())).isEqualTo(PercentileUtil.Stats.EMPTY);
        assertThat(PercentileUtil.stats(null)).isEqualTo(PercentileUtil.Stats.EMPTY);
    }

    @Test
    void computesPercentilesOverOneToOneHundred() {
        List<Long> values = java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList();

        PercentileUtil.Stats stats = PercentileUtil.stats(values);

        assertThat(stats.count()).isEqualTo(100);
        assertThat(stats.min()).isEqualTo(1);
        assertThat(stats.max()).isEqualTo(100);
        assertThat(stats.p50()).isEqualTo(50);
        assertThat(stats.p95()).isEqualTo(95);
        assertThat(stats.p99()).isEqualTo(99);
    }

    @Test
    void sortsUnsortedInputBeforeComputingPercentiles() {
        PercentileUtil.Stats stats = PercentileUtil.stats(List.of(5L, 1L, 3L));

        assertThat(stats.count()).isEqualTo(3);
        assertThat(stats.min()).isEqualTo(1);
        assertThat(stats.max()).isEqualTo(5);
        assertThat(stats.p50()).isEqualTo(3);
    }

    @Test
    void singleValueReturnsThatValueForEveryPercentile() {
        PercentileUtil.Stats stats = PercentileUtil.stats(List.of(42L));

        assertThat(stats.min()).isEqualTo(42);
        assertThat(stats.p50()).isEqualTo(42);
        assertThat(stats.p95()).isEqualTo(42);
        assertThat(stats.p99()).isEqualTo(42);
        assertThat(stats.max()).isEqualTo(42);
    }
}
