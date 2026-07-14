package com.example.amrkpi.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Per-interval (default 1s) rollup of a high-frequency histogram bucket, keyed by
 * (runId, region, operation). Persisting every raw sample at session-store intensity (tens of
 * thousands of ops/sec) would make the harness bottleneck on its own instrumentation — so only
 * these rollups are persisted; reports and charts are built from them.
 */
@Entity
@Table(name = "metric_rollup", indexes = {
        @Index(name = "idx_rollup_run_op_ts", columnList = "runId,operation,windowStart"),
        @Index(name = "idx_rollup_ts", columnList = "windowStart")
})
@Getter
@Setter
@NoArgsConstructor
public class MetricRollup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String runId;

    /** Region/endpoint name this rollup pertains to (e.g. "canada-central"), or "n/a". */
    @Column(nullable = false)
    private String region;

    /** e.g. "loadgen.get", "loadgen.set", "network.ping" */
    @Column(nullable = false)
    private String operation;

    @Column(nullable = false)
    private Instant windowStart;

    @Column(nullable = false)
    private Instant windowEnd;

    /** True for rollups falling inside the run's configured warm-up period. */
    private boolean warmUp;

    @Column(nullable = false)
    private long successCount;

    private long minMicros;
    private long p50Micros;
    private long p95Micros;
    private long p99Micros;
    private long p999Micros;
    private long maxMicros;

    /** Jackson-serialized Map<ErrorCategory, Long> of error counts in this window. */
    @Lob
    @Column(name = "error_counts_json")
    private String errorCountsJson;
}
