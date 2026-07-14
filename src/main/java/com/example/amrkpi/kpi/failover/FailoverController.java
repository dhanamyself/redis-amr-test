package com.example.amrkpi.kpi.failover;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * REST surface for KPI 4 (failover ability). {@code /induce} and {@code /restore} drive a
 * clearly-labeled <strong>controlled failure simulation</strong> — application code cannot take a
 * real AMR instance down, so this forces the targeted endpoint unhealthy via the real, public
 * {@code Database.setDisabled(...)} API (see {@link FailoverSimulationService}), which drives the
 * client through the same failover path a genuine health-check failure would. Run the load
 * generator (KPI 3) concurrently and induce/restore mid-run so {@code /report} can correlate the
 * transition window against real traffic. Sequence diagram: {@code docs/ARCHITECTURE.md § 2}.
 */
@RestController
@RequestMapping("/kpi/failover")
public class FailoverController {

    private final FailoverSimulationService simulationService;
    private final FailoverService failoverService;

    public FailoverController(FailoverSimulationService simulationService, FailoverService failoverService) {
        this.simulationService = simulationService;
        this.failoverService = failoverService;
    }

    public record InduceRequest(String region, String runId) {
    }

    public record RestoreRequest(String region) {
    }

    /**
     * Forces the given region's AMR endpoint unhealthy, triggering a real client-side failover to
     * the other region.
     *
     * @param request the region to fail, and an optional active load-test run ID to correlate
     *                subsequent failover events with
     * @return the timestamp the induced failure was applied
     */
    @PostMapping("/induce")
    public Instant induce(@RequestBody InduceRequest request) {
        return simulationService.induce(request.region(), request.runId());
    }

    /**
     * Restores a previously-induced region, allowing the client to fail back once the configured
     * grace period elapses.
     *
     * @param request the region to restore
     * @return the timestamp the restore was applied
     */
    @PostMapping("/restore")
    public Instant restore(@RequestBody RestoreRequest request) {
        return simulationService.restore(request.region());
    }

    /**
     * Reconstructs the induced-failure timeline from persisted failover-transition events: time
     * from induced failure to failover complete, and from restore to failback complete, plus
     * error/latency stats from the correlated load test during each transition window.
     *
     * @param region the region that was induced unhealthy
     * @param runId  optional load-test run ID whose rollups should be correlated against the transition windows
     * @param from   window start; defaults to one hour before {@code to}
     * @param to     window end; defaults to now
     * @return the induce/failover/restore/failback timeline with correlated transition stats
     */
    @GetMapping("/report")
    public FailoverReport report(
            @RequestParam String region,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(1, ChronoUnit.HOURS);
        return failoverService.report(region, runId, effectiveFrom, effectiveTo);
    }
}
