package com.example.amrkpi.kpi.circuitbreaker;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * REST surface for KPI 5 (circuit breaker). Reads live Resilience4j breaker state per AMR
 * endpoint and reconstructs time-in-state from persisted {@code BREAKER_STATE_TRANSITION} events
 * — see {@link BreakerService} and the state-transition listener registered in
 * {@code AmrRedisClientConfig}. Sequence diagram: {@code docs/ARCHITECTURE.md § 2}.
 */
@RestController
@RequestMapping("/kpi/circuit-breaker")
public class BreakerController {

    private final BreakerService service;

    public BreakerController(BreakerService service) {
        this.service = service;
    }

    /**
     * Live breaker state per region, read directly from the underlying Resilience4j
     * {@code CircuitBreaker} instances (not from persisted history).
     *
     * @return region name to state (e.g. {@code CLOSED}, {@code OPEN}, {@code FORCED_OPEN})
     */
    @GetMapping("/status")
    public Map<String, String> status() {
        return service.currentStates();
    }

    /**
     * Reconstructs how long the breaker for one region spent in each state over a time window,
     * from every persisted transition event.
     *
     * @param region the region name (e.g. {@code canada-central})
     * @param from   window start; defaults to one hour before {@code to}
     * @param to     window end; defaults to now
     * @return current state, time-in-state per state, and the transition count in the window
     */
    @GetMapping("/report")
    public BreakerTimeInStateReport report(
            @RequestParam String region,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(1, ChronoUnit.HOURS);
        return service.report(region, effectiveFrom, effectiveTo);
    }
}
