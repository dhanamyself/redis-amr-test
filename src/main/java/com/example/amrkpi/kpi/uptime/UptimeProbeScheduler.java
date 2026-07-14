package com.example.amrkpi.kpi.uptime;

import com.example.amrkpi.metrics.ErrorCategory;
import com.example.amrkpi.metrics.EventRecorder;
import com.example.amrkpi.persistence.entity.EventCategory;
import com.example.amrkpi.persistence.entity.Run;
import com.example.amrkpi.redis.AmrEndpoints;
import com.example.amrkpi.redis.ProbeConnectionFactory.ProbeClients;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import redis.clients.jedis.RedisClient;

import java.util.Map;

/**
 * KPI 1 — independent PING per endpoint on dedicated probe connections, every
 * {@code amrkpi.probes.uptime-interval-seconds} (default 5s). Every raw sample is persisted
 * (low-frequency path) so the outage timeline can be reconstructed exactly.
 */
@Component
public class UptimeProbeScheduler {

    private final AmrEndpoints endpoints;
    private final ProbeClients probeClients;
    private final EventRecorder eventRecorder;

    public UptimeProbeScheduler(AmrEndpoints endpoints, ProbeClients probeClients, EventRecorder eventRecorder) {
        this.endpoints = endpoints;
        this.probeClients = probeClients;
        this.eventRecorder = eventRecorder;
    }

    @Scheduled(fixedRateString = "#{${amrkpi.probes.uptime-interval-seconds} * 1000}")
    public void probeAll() {
        probeOne(endpoints.localName());
        probeOne(endpoints.failoverName());
    }

    private void probeOne(String regionName) {
        RedisClient client = probeClients.get(regionName);
        long start = System.nanoTime();
        try {
            client.ping();
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            eventRecorder.record(Run.BACKGROUND_RUN_ID, EventCategory.UPTIME_PROBE, regionName,
                    "UP", elapsedMillis, null, Map.of());
        } catch (Exception e) {
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            ErrorCategory category = ErrorCategory.classify(e);
            eventRecorder.record(Run.BACKGROUND_RUN_ID, EventCategory.UPTIME_PROBE, regionName,
                    "DOWN", elapsedMillis, category, Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}
