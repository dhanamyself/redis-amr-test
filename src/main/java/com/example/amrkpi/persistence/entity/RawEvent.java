package com.example.amrkpi.persistence.entity;

import com.example.amrkpi.metrics.ErrorCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Low-frequency paths (uptime pings, geo-replication probes, failover events, breaker
 * transitions, token renewals, consistency-test outcomes) persist every raw sample — these are
 * rare and their individual timelines matter, unlike high-frequency load-generator ops which are
 * only ever persisted as rollups (see MetricRollup).
 *
 * detailJson carries category-specific fields (e.g. staleness window millis, which region's
 * value survived a conflict, the trigger context for a breaker transition) as a small Jackson
 * map so this one table serves every low-frequency KPI without per-KPI schema duplication.
 */
@Entity
@Table(name = "raw_event", indexes = {
        @Index(name = "idx_raw_event_category_ts", columnList = "category,timestamp"),
        @Index(name = "idx_raw_event_run", columnList = "runId")
})
@Getter
@Setter
@NoArgsConstructor
public class RawEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventCategory category;

    /** Endpoint/region name this event pertains to, if applicable (e.g. "canada-central"). */
    private String region;

    @Column(nullable = false)
    private Instant timestamp;

    /** Elapsed duration this event measured, when applicable (e.g. replication lag, renewal duration). */
    private Long durationMillis;

    /** e.g. UP/DOWN, SUCCESS/FAILURE, CLOSED/OPEN/HALF_OPEN, CONVERGED/DIVERGED */
    private String outcome;

    @Enumerated(EnumType.STRING)
    private ErrorCategory errorCategory;

    @Lob
    @Column(name = "detail_json")
    private String detailJson;
}
