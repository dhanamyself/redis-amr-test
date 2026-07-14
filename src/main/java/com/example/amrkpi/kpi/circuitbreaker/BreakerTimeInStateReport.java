package com.example.amrkpi.kpi.circuitbreaker;

import java.time.Instant;
import java.util.Map;

public record BreakerTimeInStateReport(
        String region,
        Instant from,
        Instant to,
        String currentState,
        Map<String, Long> timeInStateMillis,
        long transitionCount
) {
}
