package com.example.amrkpi.kpi.networktime;

import com.example.amrkpi.metrics.ErrorCategory;
import com.example.amrkpi.metrics.OperationRecorder;
import com.example.amrkpi.persistence.entity.Run;
import com.example.amrkpi.redis.AmrEndpoints;
import com.example.amrkpi.redis.ProbeConnectionFactory.ProbeClients;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import redis.clients.jedis.RedisClient;

/**
 * KPI 7 — high-frequency bare PING per region on dedicated probe connections, isolating
 * round-trip network time from command processing. High-frequency, so it feeds the rollup path
 * (OperationRecorder), not raw event persistence.
 */
@Component
public class NetworkTimeProbeScheduler {

    public static final String OPERATION = "network.ping";

    private final AmrEndpoints endpoints;
    private final ProbeClients probeClients;
    private final OperationRecorder recorder;

    public NetworkTimeProbeScheduler(AmrEndpoints endpoints, ProbeClients probeClients, OperationRecorder recorder) {
        this.endpoints = endpoints;
        this.probeClients = probeClients;
        this.recorder = recorder;
    }

    @Scheduled(fixedRateString = "${amrkpi.probes.network-time-interval-millis}")
    public void probeAll() {
        probeOne(endpoints.localName());
        probeOne(endpoints.failoverName());
    }

    private void probeOne(String regionName) {
        RedisClient client = probeClients.get(regionName);
        long start = System.nanoTime();
        try {
            client.ping();
            recorder.recordSuccess(Run.BACKGROUND_RUN_ID, regionName, OPERATION, System.nanoTime() - start);
        } catch (Exception e) {
            ErrorCategory category = ErrorCategory.classify(e);
            recorder.recordError(Run.BACKGROUND_RUN_ID, regionName, OPERATION, category);
        }
    }
}
