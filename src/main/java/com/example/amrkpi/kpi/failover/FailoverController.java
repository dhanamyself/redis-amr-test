package com.example.amrkpi.kpi.failover;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

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

    @PostMapping("/induce")
    public Instant induce(@RequestBody InduceRequest request) {
        return simulationService.induce(request.region(), request.runId());
    }

    @PostMapping("/restore")
    public Instant restore(@RequestBody RestoreRequest request) {
        return simulationService.restore(request.region());
    }

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
