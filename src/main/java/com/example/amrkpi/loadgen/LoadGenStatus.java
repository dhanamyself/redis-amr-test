package com.example.amrkpi.loadgen;

import java.time.Instant;

public record LoadGenStatus(
        boolean running,
        String runId,
        LoadGenConfig config,
        Instant startedAt,
        long achievedCount,
        double achievedOpsPerSec
) {
    public static LoadGenStatus idle() {
        return new LoadGenStatus(false, null, null, null, 0, 0);
    }
}
