package com.example.amrkpi.web;

import com.example.amrkpi.config.AmrKpiProperties;
import com.example.amrkpi.kpi.consistency.ConsistencyService;
import com.example.amrkpi.kpi.failover.FailoverSimulationService;
import com.example.amrkpi.kpi.geolatency.GeoReplicationService;
import com.example.amrkpi.loadgen.LoadGenConfig;
import com.example.amrkpi.loadgen.LoadGenService;
import com.example.amrkpi.redis.AmrEndpoints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One-click test presets (see build spec "Reporting & dashboard: Test presets"). Each preset
 * starts (or reuses) a load-generator run and returns immediately with the run ID — long-running
 * orchestration (failover-under-load's induce/restore timing, the soak duration) continues on a
 * background virtual thread; poll /loadgen/status and the individual KPI report endpoints for
 * progress, exactly as with a manually-started run.
 */
@Service
public class PresetService {

    private static final Logger log = LoggerFactory.getLogger(PresetService.class);

    private final LoadGenService loadGenService;
    private final GeoReplicationService geoReplicationService;
    private final ConsistencyService consistencyService;
    private final FailoverSimulationService failoverSimulationService;
    private final AmrEndpoints endpoints;
    private final AmrKpiProperties props;

    public PresetService(LoadGenService loadGenService, GeoReplicationService geoReplicationService,
                          ConsistencyService consistencyService, FailoverSimulationService failoverSimulationService,
                          AmrEndpoints endpoints, AmrKpiProperties props) {
        this.loadGenService = loadGenService;
        this.geoReplicationService = geoReplicationService;
        this.consistencyService = consistencyService;
        this.failoverSimulationService = failoverSimulationService;
        this.endpoints = endpoints;
        this.props = props;
    }

    public record PresetResult(String preset, String runId, String note) {
    }

    /** (a) smoke — 60s light load, all KPIs sanity-checked. */
    public PresetResult smoke() {
        LoadGenConfig config = new LoadGenConfig(60, 8, 0, props.getWorkload().getReadWriteRatio(),
                100, props.getWorkload().getValueSizeMeanBytes(), props.getWorkload().getValueSizeMaxBytes(),
                props.getWorkload().getHotSetFraction(), props.getWorkload().getHotSetAccessFraction(),
                props.getWorkload().getTtlSeconds(), props.getWorkload().isSlidingExpiration(), 5);
        String runId = loadGenService.start(config);

        geoReplicationService.runBothDirections(runId);
        consistencyService.staleness(endpoints.localName(), endpoints.failoverName(), runId);
        try {
            consistencyService.concurrentConflict(runId, null);
        } catch (Exception e) {
            log.warn("Smoke preset consistency conflict probe failed", e);
        }

        return new PresetResult("smoke", runId,
                "60s light load + one geo-replication round-trip + one consistency staleness/conflict probe, all tagged to this runId.");
    }

    /** (b) session peak — high-concurrency 15-minute run at the session workload shape. */
    public PresetResult sessionPeak() {
        LoadGenConfig config = new LoadGenConfig(900, 128, 0, props.getWorkload().getReadWriteRatio(),
                props.getWorkload().getDefaultKeySpaceSize(), props.getWorkload().getValueSizeMeanBytes(),
                props.getWorkload().getValueSizeMaxBytes(), props.getWorkload().getHotSetFraction(),
                props.getWorkload().getHotSetAccessFraction(), props.getWorkload().getTtlSeconds(),
                props.getWorkload().isSlidingExpiration(), props.getWorkload().getWarmUpSeconds());
        String runId = loadGenService.start(config);
        return new PresetResult("session-peak", runId, "15-minute closed-loop run at concurrency=128, session workload shape.");
    }

    /** (c) failover under load — session peak plus an induced failover mid-run. */
    public PresetResult failoverUnderLoad() {
        PresetResult base = sessionPeak();
        String runId = base.runId();
        String regionToFail = endpoints.localName();

        Thread.ofVirtual().name("preset-failover-" + runId).start(() -> {
            try {
                Thread.sleep(120_000); // let load stabilize past warm-up before inducing failure
                failoverSimulationService.induce(regionToFail, runId);
                Thread.sleep(60_000); // observe failover behavior under load
                failoverSimulationService.restore(regionToFail);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("failover-under-load preset orchestration failed for run {}", runId, e);
            }
        });

        return new PresetResult("failover-under-load", runId,
                "Session-peak run with " + regionToFail + " forced unhealthy 120s in, restored 60s later. "
                        + "Poll /kpi/failover/report?region=" + regionToFail + "&runId=" + runId + " once it completes.");
    }

    /** (d) soak — >= 2x token lifetime at moderate load, KPI 8 focus. */
    public PresetResult soak() {
        int durationSeconds = props.getTokenLifecycle().getAssumedTokenLifetimeSeconds()
                * props.getTokenLifecycle().getSoakMultipleOfTokenLifetime();
        LoadGenConfig config = new LoadGenConfig(durationSeconds, 24, 0, props.getWorkload().getReadWriteRatio(),
                props.getWorkload().getDefaultKeySpaceSize(), props.getWorkload().getValueSizeMeanBytes(),
                props.getWorkload().getValueSizeMaxBytes(), props.getWorkload().getHotSetFraction(),
                props.getWorkload().getHotSetAccessFraction(), props.getWorkload().getTtlSeconds(),
                props.getWorkload().isSlidingExpiration(), props.getWorkload().getWarmUpSeconds());
        String runId = loadGenService.start(config);
        return new PresetResult("soak", runId, "Moderate load for " + durationSeconds
                + "s (>= 2x assumed token lifetime). Check /kpi/token-lifecycle?runId=" + runId + " for renewal correlation.");
    }

    /** (e) consistency probe — KPI 9 suite; automatically runs "under load" if a load test is active, else idle. */
    public PresetResult consistencyProbe() {
        var status = loadGenService.status();
        String runId = status.running() ? status.runId() : null;

        consistencyService.staleness(endpoints.localName(), endpoints.failoverName(), runId);
        consistencyService.staleness(endpoints.failoverName(), endpoints.localName(), runId);
        try {
            consistencyService.concurrentConflict(runId, null);
        } catch (Exception e) {
            log.warn("Consistency-probe preset conflict probe failed", e);
        }

        return new PresetResult("consistency-probe", runId,
                status.running() ? "Ran under the active load test (runId=" + runId + ")." : "Ran idle (no load test was active).");
    }
}
