package com.example.amrkpi.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One row per load test / preset execution. The always-on background probes (uptime,
 * network-time, scheduled geo-replication) are tagged with the constant runId "background"
 * rather than a Run row, per the Metrics architecture spec.
 *
 * configJson captures the FULL effective configuration for the run (concurrency, ratios, value
 * sizes, pool config, breaker config, token scope in effect, target region(s), preset name) so
 * results stay reproducible and comparable across runs.
 */
@Entity
@Table(name = "run")
@Getter
@Setter
@NoArgsConstructor
public class Run {

    public static final String BACKGROUND_RUN_ID = "background";

    @Id
    private String id;

    /** e.g. "loadgen", "smoke", "session-peak", "failover-under-load", "soak", "consistency-probe" */
    @Column(nullable = false)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant endedAt;

    @Lob
    @Column(name = "config_json")
    private String configJson;

    private String notes;
}
