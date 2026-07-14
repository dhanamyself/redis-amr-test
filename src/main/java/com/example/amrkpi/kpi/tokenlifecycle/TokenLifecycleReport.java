package com.example.amrkpi.kpi.tokenlifecycle;

import java.time.Instant;
import java.util.List;

public record TokenLifecycleReport(
        Instant from,
        Instant to,
        long successCount,
        long failureCount,
        List<TokenRenewalCorrelation> renewals
) {
}
