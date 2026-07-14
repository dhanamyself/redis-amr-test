package com.example.amrkpi.kpi.tokenlifecycle;

import com.example.amrkpi.report.AggregateStats;

import java.time.Instant;

public record TokenRenewalCorrelation(
        Instant renewedAt,
        String outcome,
        String user,
        Instant tokenExpiresAt,
        Long tokenTtlMillis,
        /** Rollup stats across the correlation window around this renewal — null if no runId was supplied. */
        AggregateStats aroundRenewalWindow
) {
}
