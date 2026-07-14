package com.example.amrkpi.kpi.tokenlifecycle;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@RestController
public class TokenLifecycleController {

    private final TokenLifecycleService service;

    public TokenLifecycleController(TokenLifecycleService service) {
        this.service = service;
    }

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
