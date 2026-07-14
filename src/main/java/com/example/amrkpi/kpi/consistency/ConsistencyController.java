package com.example.amrkpi.kpi.consistency;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * REST surface for KPI 9 (consistency &amp; conflict). Active geo-replication is eventually
 * consistent and resolves concurrent writes via CRDT conflict resolution — these two probes
 * quantify the resulting risk window for opaque session blobs. See {@link ConsistencyService} and
 * the "architectural conclusion" note in the README (prefer region-affinity for session writes).
 */
@RestController
@RequestMapping("/kpi/consistency")
public class ConsistencyController {

    private final ConsistencyService service;

    public ConsistencyController(ConsistencyService service) {
        this.service = service;
    }

    public record StalenessRequest(String writeRegion, String readRegion, String runId) {
    }

    public record ConflictRequest(String runId, Integer writeGapMillis) {
    }

    /**
     * Writes a key to one region, reads it immediately from the other, and tracks how long the
     * stale window lasts — a read-side complement to KPI 2's write-side replication-lag probe.
     *
     * @param request the write region, the read region, and an optional run ID to tag the sample with
     * @return whether the immediate read was stale, whether it eventually became consistent, and
     *         the observed stale-window duration in milliseconds
     */
    @PostMapping("/staleness")
    public StalenessResult staleness(@RequestBody StalenessRequest request) {
        return service.staleness(request.writeRegion(), request.readRegion(), request.runId());
    }

    /**
     * Writes different values to the same key in both regions within a configurable window
     * (including near-simultaneous), then polls until both regions converge or a timeout elapses.
     *
     * @param request optional run ID to tag the sample with, and an optional override of the
     *                configured concurrent-write window in milliseconds
     * @return which value (if either) survived, whether the regions converged, and how long
     *         convergence took
     */
    @PostMapping("/conflict")
    public ConflictResult conflict(@RequestBody(required = false) ConflictRequest request) throws Exception {
        String runId = request != null ? request.runId() : null;
        Integer writeGapMillis = request != null ? request.writeGapMillis() : null;
        return service.concurrentConflict(runId, writeGapMillis);
    }

    /**
     * Aggregates every staleness and conflict probe outcome over a time window: stale-at-t0
     * counts, staleness-window percentiles, and converged/diverged counts with convergence-time
     * percentiles.
     *
     * @param from window start; defaults to one hour before {@code to}
     * @param to   window end; defaults to now
     * @return aggregate staleness and conflict-outcome statistics
     */
    @GetMapping("/report")
    public ConsistencyReport report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(1, ChronoUnit.HOURS);
        return service.report(effectiveFrom, effectiveTo);
    }
}
