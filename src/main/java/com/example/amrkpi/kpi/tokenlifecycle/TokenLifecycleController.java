package com.example.amrkpi.kpi.tokenlifecycle;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * REST surface for KPI 8 (token lifecycle). Correlates every Entra ID token acquisition/renewal
 * (recorded by {@code AmrAuthConfig}'s {@code AuthXManager} hooks) against the concurrent load
 * test's rollups, to answer: does token renewal under sustained load cause any error blip or
 * latency spike on pooled connections? Use the {@code soak} preset (spans ≥2× the assumed token
 * lifetime) to guarantee at least one real renewal happens during a load test. Sequence diagram:
 * {@code docs/ARCHITECTURE.md § 1}.
 */
@RestController
public class TokenLifecycleController {

    private final TokenLifecycleService service;

    public TokenLifecycleController(TokenLifecycleService service) {
        this.service = service;
    }

    /**
     * Every token renewal in the window, each with a latency/error snapshot of the correlated
     * load test's rollups in a window around the renewal instant.
     *
     * @param runId         optional load-test run ID to correlate renewals against; without it,
     *                      renewal events are still listed but with no correlated rollup stats
     * @param windowSeconds half-width, in seconds, of the correlation window around each renewal
     *                      (default 10s each side)
     * @param from          window start; defaults to six hours before {@code to}
     * @param to            window end; defaults to now
     * @return renewal success/failure counts and the per-renewal correlation details
     */
    @GetMapping("/kpi/token-lifecycle")
    public TokenLifecycleReport report(
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) Integer windowSeconds,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(6, ChronoUnit.HOURS);
        return service.report(runId, effectiveFrom, effectiveTo, windowSeconds);
    }
}
