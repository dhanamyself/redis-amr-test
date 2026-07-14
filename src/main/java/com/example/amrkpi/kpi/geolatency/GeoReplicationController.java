package com.example.amrkpi.kpi.geolatency;

import com.example.amrkpi.redis.AmrEndpoints;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * REST surface for KPI 2 (geo-replication time). Writes a probe key to one region and polls the
 * other with bounded timeout/backoff until visible — see {@link GeoReplicationService}. Every
 * cross-region timing figure here is an empirically measured sample; AMR active geo-replication
 * has no SLA on sync time, so nothing in this app assumes a fixed lag.
 */
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

    /**
     * Runs a replication-lag probe in both directions (local→failover and failover→local) on
     * demand. Also useful during an active load test — pass the load test's run ID to tag the
     * samples and observe lag under pressure vs. idle.
     *
     * @param request optional run ID to tag both samples with
     * @return the two directional reports covering just this probe's samples
     */
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

    /**
     * Aggregates replication-lag percentiles (p50/p95/p99/max) and timeout count for one
     * direction over a time window, from persisted probe samples (background-scheduled and
     * on-demand alike).
     *
     * @param direction a direction string as produced by {@link GeoReplicationService#direction},
     *                  e.g. {@code canada-central->canada-east}
     * @param from      window start; defaults to one hour before {@code to}
     * @param to        window end; defaults to now
     * @return latency percentiles and timeout count for the requested direction
     */
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
