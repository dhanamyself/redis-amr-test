package com.example.amrkpi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Harness-side config: metrics rollups, session workload model defaults, probe cadence. */
@Data
@ConfigurationProperties(prefix = "amrkpi")
public class AmrKpiProperties {

    private Metrics metrics = new Metrics();
    private Workload workload = new Workload();
    private Probes probes = new Probes();
    private TokenLifecycle tokenLifecycle = new TokenLifecycle();
    private Consistency consistency = new Consistency();
    private CacheAside cacheAside = new CacheAside();

    @Data
    public static class Metrics {
        private int rollupIntervalSeconds = 1;
        private List<Double> percentiles = List.of(0.5, 0.95, 0.99, 0.999);
    }

    @Data
    public static class Workload {
        private int valueSizeMeanBytes = 2048;
        private int valueSizeMaxBytes = 20480;
        private int ttlSeconds = 1800;
        private boolean slidingExpiration = true;
        private double hotSetFraction = 0.2;
        private double hotSetAccessFraction = 0.8;
        private double readWriteRatio = 0.8;
        private int defaultKeySpaceSize = 100_000;
        private int warmUpSeconds = 30;
    }

    @Data
    public static class Probes {
        private int uptimeIntervalSeconds = 5;
        private int networkTimeIntervalMillis = 200;
        private int geoReplicationIntervalSeconds = 30;
        private int geoReplicationPollTimeoutMillis = 5000;
        private int geoReplicationPollBackoffMillis = 50;
    }

    @Data
    public static class TokenLifecycle {
        private int soakMultipleOfTokenLifetime = 2;
        /** Standard Entra ID access token lifetime; used to size the soak preset's duration. */
        private int assumedTokenLifetimeSeconds = 3600;
    }

    @Data
    public static class Consistency {
        private int concurrentWriteWindowMillis = 50;
        private int convergencePollTimeoutMillis = 10000;
    }

    @Data
    public static class CacheAside {
        /** Simulated network+query latency for the H2-backed mock source-of-truth (it's a stand-in — see README). */
        private int sourceOfTruthSimulatedLatencyMillis = 20;
        private int ttlSeconds = 300;
        private int valueSizeBytes = 512;
    }
}
