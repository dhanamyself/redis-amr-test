package com.example.amrkpi.kpi.failover;

import com.example.amrkpi.persistence.entity.EventCategory;
import com.example.amrkpi.persistence.entity.RawEvent;
import com.example.amrkpi.persistence.repository.MetricRollupRepository;
import com.example.amrkpi.persistence.repository.RawEventRepository;
import com.example.amrkpi.report.AggregateStats;
import com.example.amrkpi.report.RollupAggregator;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reconstructs the KPI 4 failover/failback timeline purely from persisted FAILOVER_TRANSITION
 * events in the requested window — induced-failure and restore markers (from
 * FailoverSimulationService) plus the real DatabaseSwitchEvent-derived SWITCHED_ACTIVE events
 * (from AmrRedisClientConfig's listener), which carry no timestamp of their own so every one was
 * stamped the instant it was observed.
 */
@Service
public class FailoverService {

    private final RawEventRepository rawEventRepository;
    private final MetricRollupRepository rollupRepository;
    private final RollupAggregator aggregator;

    public FailoverService(RawEventRepository rawEventRepository, MetricRollupRepository rollupRepository,
                            RollupAggregator aggregator) {
        this.rawEventRepository = rawEventRepository;
        this.rollupRepository = rollupRepository;
        this.aggregator = aggregator;
    }

    public FailoverReport report(String region, String runId, Instant from, Instant to) {
        List<RawEvent> events = rawEventRepository.findByCategoryAndTimestampBetweenOrderByTimestampAsc(
                EventCategory.FAILOVER_TRANSITION, from, to);

        Optional<RawEvent> induced = events.stream()
                .filter(e -> region.equals(e.getRegion()) && "INDUCED_FAILURE_START".equals(e.getOutcome()))
                .findFirst();
        Instant inducedAt = induced.map(RawEvent::getTimestamp).orElse(null);

        Instant failoverDetectedAt = inducedAt == null ? null : events.stream()
                .filter(e -> "SWITCHED_ACTIVE".equals(e.getOutcome()))
                .filter(e -> e.getTimestamp().isAfter(inducedAt))
                .filter(e -> !region.equals(e.getRegion()))
                .map(RawEvent::getTimestamp)
                .findFirst().orElse(null);

        Optional<RawEvent> restored = events.stream()
                .filter(e -> region.equals(e.getRegion()) && "INDUCED_FAILURE_RESTORED".equals(e.getOutcome()))
                .findFirst();
        Instant restoredAt = restored.map(RawEvent::getTimestamp).orElse(null);

        Instant failbackDetectedAt = restoredAt == null ? null : events.stream()
                .filter(e -> "SWITCHED_ACTIVE".equals(e.getOutcome()))
                .filter(e -> e.getTimestamp().isAfter(restoredAt))
                .filter(e -> region.equals(e.getRegion()))
                .map(RawEvent::getTimestamp)
                .findFirst().orElse(null);

        Long failoverDurationMillis = durationMillis(inducedAt, failoverDetectedAt);
        Long failbackDurationMillis = durationMillis(restoredAt, failbackDetectedAt);

        AggregateStats duringFailover = windowStats(runId, inducedAt, failoverDetectedAt != null ? failoverDetectedAt : to);
        AggregateStats duringFailback = windowStats(runId, restoredAt, failbackDetectedAt != null ? failbackDetectedAt : to);

        return new FailoverReport(region, inducedAt, failoverDetectedAt, failoverDurationMillis,
                restoredAt, failbackDetectedAt, failbackDurationMillis, duringFailover, duringFailback);
    }

    private Long durationMillis(Instant start, Instant end) {
        if (start == null || end == null) {
            return null;
        }
        return Duration.between(start, end).toMillis();
    }

    private AggregateStats windowStats(String runId, Instant start, Instant end) {
        if (runId == null || start == null || end == null) {
            return null;
        }
        var rollups = rollupRepository.findByRunIdOrderByWindowStartAsc(runId).stream()
                .filter(r -> !r.getWindowStart().isBefore(start) && !r.getWindowStart().isAfter(end))
                .toList();
        return aggregator.aggregate(rollups, false);
    }
}
