package com.example.amrkpi.loadgen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RunService serializes the effective LoadGenConfig into Run.configJson at load-test start, and
 * ThroughputService deserializes it back for every KPI 3 report — but
 * ThroughputService.readConfig() swallows deserialization exceptions and returns null on
 * failure, so a broken round trip (record/Jackson mismatch, a renamed field breaking old runs'
 * stored JSON, etc.) would silently show "no config" on a report instead of ever throwing
 * anywhere. This test exists to catch exactly that class of silent failure directly, since
 * nothing else in the suite would.
 */
class LoadGenConfigJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void survivesASerializeDeserializeRoundTrip() throws Exception {
        LoadGenConfig original = new LoadGenConfig(
                900, 128, 5000, 0.8, 100_000, 2048, 20_480, 0.2, 0.8, 1800, true, 30);

        String json = objectMapper.writeValueAsString(original);
        LoadGenConfig roundTripped = objectMapper.readValue(json, LoadGenConfig.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void closedLoopConfigWithZeroTargetRateRoundTrips() throws Exception {
        LoadGenConfig closedLoop = new LoadGenConfig(
                60, 8, 0, 0.8, 100, 2048, 20_480, 0.2, 0.8, 1800, false, 5);

        String json = objectMapper.writeValueAsString(closedLoop);
        LoadGenConfig roundTripped = objectMapper.readValue(json, LoadGenConfig.class);

        assertThat(roundTripped).isEqualTo(closedLoop);
        assertThat(roundTripped.isOpenLoop()).isFalse();
    }

    @Test
    void openLoopFlagReflectsTargetOpsPerSec() {
        assertThat(new LoadGenConfig(1, 1, 0, 0.8, 1, 1, 1, 0.2, 0.8, 1, true, 0).isOpenLoop()).isFalse();
        assertThat(new LoadGenConfig(1, 1, 500, 0.8, 1, 1, 1, 0.2, 0.8, 1, true, 0).isOpenLoop()).isTrue();
    }
}
