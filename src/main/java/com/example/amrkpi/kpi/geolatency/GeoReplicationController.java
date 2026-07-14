package com.example.amrkpi.kpi.geolatency;

import com.example.amrkpi.redis.AmrEndpoints;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/kpi/geo-replication-time")
public class GeoReplicationController {

    private final GeoReplicationService service;
    private final AmrEndpoints endpoints;

    public GeoReplicationController(GeoReplicationService service, AmrEndpoints endpoints) {
        this.service = service;
        this.endpoints = endpoints;
    }

    public record RunRequest(String runId) {
    }

    /** Runs both directions on demand — also used during load tests, tagged with the concurrent runId. */
    @PostMapping("/run")
    public List<GeoReplicationReport> run(@RequestBody(required = false) RunRequest request) {
        String runId = request != null ? request.runId() : null;
        service.runProbe(endpoints.localName(), endpoints.failoverName(), runId);
        service.runProbe(endpoints.failoverName(), endpoints.localName(), runId);
        Instant now = Instant.now();
        Instant from = now.minusSeconds(5);
        return List.of(
                service.report(service.direction(endpoints.localName(), endpoints.failoverName()), from, now),
                service.report(service.direction(endpoints.failoverName(), endpoints.localName()), from, now)
        );
    }

    @GetMapping
    public GeoReplicationReport report(
            @RequestParam String direction,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(1, ChronoUnit.HOURS);
        return service.report(direction, effectiveFrom, effectiveTo);
    }
}
