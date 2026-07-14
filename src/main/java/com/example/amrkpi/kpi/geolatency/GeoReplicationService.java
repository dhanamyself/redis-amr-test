package com.example.amrkpi.kpi.geolatency;

import com.example.amrkpi.metrics.ErrorCategory;
import com.example.amrkpi.metrics.EventRecorder;
import com.example.amrkpi.persistence.entity.EventCategory;
import com.example.amrkpi.persistence.entity.RawEvent;
import com.example.amrkpi.persistence.entity.Run;
import com.example.amrkpi.persistence.repository.RawEventRepository;
import com.example.amrkpi.redis.AmrEndpoints;
import com.example.amrkpi.redis.ProbeConnectionFactory.ProbeClients;
import com.example.amrkpi.report.PercentileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * KPI 2 — geo-replication lag. Writes a probe key to one region, polls the other with bounded
 * timeout + backoff until visible; elapsed time is a replication lag sample. Never assumes a
 * fixed replication latency — every figure here is an empirically measured, timestamped sample,
 * per the build spec (AMR active geo-replication is eventually consistent with no sync-time SLA).
 */
@Service
public class GeoReplicationService {

    private static final Logger log = LoggerFactory.getLogger(GeoReplicationService.class);
    private static final int PROBE_KEY_TTL_SECONDS = 300;

    private final AmrEndpoints endpoints;
    private final ProbeClients probeClients;
    private final RawEventRepository rawEventRepository;
    private final EventRecorder eventRecorder;
    private final int pollTimeoutMillis;
    private final int pollBackoffMillis;

    public GeoReplicationService(AmrEndpoints endpoints, ProbeClients probeClients,
                                  RawEventRepository rawEventRepository, EventRecorder eventRecorder,
                                  com.example.amrkpi.config.AmrKpiProperties props) {
        this.endpoints = endpoints;
        this.probeClients = probeClients;
        this.rawEventRepository = rawEventRepository;
        this.eventRecorder = eventRecorder;
        this.pollTimeoutMillis = props.getProbes().getGeoReplicationPollTimeoutMillis();
        this.pollBackoffMillis = props.getProbes().getGeoReplicationPollBackoffMillis();
    }

    public String direction(String fromRegion, String toRegion) {
        return fromRegion + "->" + toRegion;
    }

    /**
     * Runs a single directional replication-lag probe and persists the sample. Every failure
     * path is recorded, not just the timeout case — a write or read that throws (connection
     * refused, auth failure, etc.) is exactly the kind of thing the error taxonomy exists to
     * classify, and letting it propagate uncaught would make the probe attempt vanish from the
     * data entirely instead of showing up as a classified failure.
     */
    public RawEvent runProbe(String fromRegion, String toRegion, String runId) {
        RedisClient writer = probeClients.get(fromRegion);
        RedisClient reader = probeClients.get(toRegion);

        String key = "probe:geo:" + UUID.randomUUID();
        String effectiveRunId = runId != null ? runId : Run.BACKGROUND_RUN_ID;
        String direction = direction(fromRegion, toRegion);
        Map<String, Object> detail = Map.of("fromRegion", fromRegion, "toRegion", toRegion, "key", key);

        long writeNanos = System.nanoTime();
        try {
            writer.set(key, Long.toString(writeNanos), SetParams.setParams().ex(PROBE_KEY_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("Geo-replication probe write to {} failed: {}", fromRegion, e.getMessage());
            return eventRecorder.record(effectiveRunId, EventCategory.GEO_REPLICATION_PROBE, direction,
                    "WRITE_FAILED", null, ErrorCategory.classify(e), detail);
        }

        long deadline = writeNanos + Duration.ofMillis(pollTimeoutMillis).toNanos();
        boolean visible = false;
        ErrorCategory readError = null;
        while (System.nanoTime() < deadline) {
            try {
                if (reader.get(key) != null) {
                    visible = true;
                    break;
                }
            } catch (Exception e) {
                readError = ErrorCategory.classify(e);
                break;
            }
            sleep(pollBackoffMillis);
        }
        long elapsedMillis = (System.nanoTime() - writeNanos) / 1_000_000;

        String outcome = visible ? "REPLICATED" : (readError != null ? "READ_FAILED" : "TIMEOUT");
        RawEvent event = eventRecorder.record(effectiveRunId, EventCategory.GEO_REPLICATION_PROBE, direction,
                outcome, elapsedMillis, readError, detail);

        if (!visible) {
            log.warn("Geo-replication probe {} did not become visible in {} ({}ms budget)", key, toRegion, pollTimeoutMillis);
        }
        // Best-effort cleanup; TTL will reclaim it regardless if this fails.
        try {
            reader.del(key);
            writer.del(key);
        } catch (Exception ignored) {
        }

        return event;
    }

    /** Runs both directions (CC->CE, CE->CC). */
    public void runBothDirections(String runId) {
        runProbe(endpoints.localName(), endpoints.failoverName(), runId);
        runProbe(endpoints.failoverName(), endpoints.localName(), runId);
    }

    public GeoReplicationReport report(String direction, Instant from, Instant to) {
        List<RawEvent> events = rawEventRepository.findByCategoryAndRegionAndTimestampBetweenOrderByTimestampAsc(
                EventCategory.GEO_REPLICATION_PROBE, direction, from, to);
        List<Long> samples = events.stream()
                .map(RawEvent::getDurationMillis)
                .filter(java.util.Objects::nonNull)
                .toList();
        long timeoutCount = events.stream().filter(e -> "TIMEOUT".equals(e.getOutcome())).count();
        PercentileUtil.Stats stats = PercentileUtil.stats(samples);
        return new GeoReplicationReport(direction, from, to, stats, timeoutCount);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
