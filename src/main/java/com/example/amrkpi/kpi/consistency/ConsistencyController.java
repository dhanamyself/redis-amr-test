package com.example.amrkpi.kpi.consistency;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

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

    @PostMapping("/staleness")
    public StalenessResult staleness(@RequestBody StalenessRequest request) {
        return service.staleness(request.writeRegion(), request.readRegion(), request.runId());
    }

    @PostMapping("/conflict")
    public ConflictResult conflict(@RequestBody(required = false) ConflictRequest request) throws Exception {
        String runId = request != null ? request.runId() : null;
        Integer writeGapMillis = request != null ? request.writeGapMillis() : null;
        return service.concurrentConflict(runId, writeGapMillis);
    }

    @GetMapping("/report")
    public ConsistencyReport report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(1, ChronoUnit.HOURS);
        return service.report(effectiveFrom, effectiveTo);
    }
}
