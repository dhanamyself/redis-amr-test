package com.example.amrkpi.kpi.uptime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * REST surface for KPI 1 (uptime). Reports reachability and the outage timeline for one region,
 * reconstructed by {@link UptimeService} from raw PING samples taken every
 * {@code amrkpi.probes.uptime-interval-seconds} (default 5s) on a dedicated probe connection —
 * never through the load generator's pool.
 */
@RestController
public class UptimeController {

    private final UptimeService uptimeService;

    public UptimeController(UptimeService uptimeService) {
        this.uptimeService = uptimeService;
    }

    /**
     * Time-weighted uptime percentage and the reconstructed outage timeline for one region over a
     * window, distinguishing "endpoint down" from "auth failed" via each outage's error category.
     *
     * @param region the region name (e.g. {@code canada-central})
     * @param from   window start; defaults to one hour before {@code to}
     * @param to     window end; defaults to now
     * @return uptime percentage, probe count, and the outage timeline
     */
    @GetMapping("/kpi/uptime")
    public UptimeReport uptime(
            @RequestParam String region,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(1, ChronoUnit.HOURS);
        return uptimeService.report(region, effectiveFrom, effectiveTo);
    }
}
