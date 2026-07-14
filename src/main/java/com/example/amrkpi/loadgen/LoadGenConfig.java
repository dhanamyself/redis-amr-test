package com.example.amrkpi.loadgen;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Full effective configuration for one load-generator run. Every field is stamped into the
 * persisted Run row (via RunService) so results stay reproducible and comparable, per the
 * Metrics architecture spec.
 */
public record LoadGenConfig(
        int durationSeconds,
        int concurrency,
        /** 0 (or null-equivalent) means closed-loop: workers run back-to-back with no pacing. */
        int targetOpsPerSec,
        double readWriteRatio,
        int keySpaceSize,
        int valueSizeMeanBytes,
        int valueSizeMaxBytes,
        double hotSetFraction,
        double hotSetAccessFraction,
        int ttlSeconds,
        boolean slidingExpiration,
        int warmUpSeconds
) {
    // Derived, not a config field — must not round-trip through Jackson. Without @JsonIgnore,
    // Jackson serializes this as an "openLoop" property (records get bean-getter treatment same
    // as classes), and then fails to deserialize the same JSON back with
    // UnrecognizedPropertyException since the canonical constructor has no matching parameter.
    // ThroughputService.readConfig() swallows that exception, so every real run's throughput
    // report would silently show a null config — caught by LoadGenConfigJsonTest's round trip.
    @JsonIgnore
    public boolean isOpenLoop() {
        return targetOpsPerSec > 0;
    }
}
