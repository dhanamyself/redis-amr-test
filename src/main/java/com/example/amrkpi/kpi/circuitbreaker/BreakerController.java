package com.example.amrkpi.kpi.circuitbreaker;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@RestController
@RequestMapping("/kpi/circuit-breaker")
public class BreakerController {

    private final BreakerService service;

    public BreakerController(BreakerService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public Map<String, String> status() {
        return service.currentStates();
    }

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
