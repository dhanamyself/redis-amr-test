package com.example.amrkpi.kpi.failover;

import com.example.amrkpi.metrics.EventRecorder;
import com.example.amrkpi.persistence.entity.EventCategory;
import com.example.amrkpi.redis.CircuitBreakerStatusService;
import com.example.amrkpi.redis.FailoverSimulationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * KPI 4 — a clearly-labeled CONTROLLED FAILURE SIMULATION, not a real outage. Application code
 * cannot take an AMR instance down, so this uses Database.setDisabled() (real public Jedis API —
 * see CircuitBreakerStatusService/AmrRedisClientConfig javadoc) to force the targeted endpoint
 * unhealthy, driving the client through the exact same failover-selection path a genuine health
 * check failure would. The README documents the two real, non-simulated drills (Azure-initiated
 * reboot/maintenance, force-unlink) the infra team can run instead, with this harness's
 * uptime/failover instrumentation as the measurement side of those.
 * <p>
 * Run this with the load generator active — the report correlates the induced window against
 * that run's rollups to show the error/latency spike during the transition.
 */
@Service
public class FailoverSimulationService {

    private static final Logger log = LoggerFactory.getLogger(FailoverSimulationService.class);

    private final CircuitBreakerStatusService breakerStatus;
    private final EventRecorder eventRecorder;
    private final FailoverSimulationState simulationState;

    public FailoverSimulationService(CircuitBreakerStatusService breakerStatus, EventRecorder eventRecorder,
                                      FailoverSimulationState simulationState) {
        this.breakerStatus = breakerStatus;
        this.eventRecorder = eventRecorder;
        this.simulationState = simulationState;
    }

    public Instant induce(String region, String runId) {
        if (runId != null) {
            simulationState.start(runId);
        }
        Instant now = Instant.now();
        eventRecorder.record(simulationState.currentRunIdOrBackground(), EventCategory.FAILOVER_TRANSITION,
                region, "INDUCED_FAILURE_START", null, null,
                Map.of("triggeredBy", "simulation", "mechanism", "Database.setDisabled(true)"));
        breakerStatus.setDisabled(region, true);
        log.warn("KPI-4 controlled failure simulation: forced {} unhealthy at {}", region, now);
        return now;
    }

    public Instant restore(String region) {
        breakerStatus.setDisabled(region, false);
        Instant now = Instant.now();
        eventRecorder.record(simulationState.currentRunIdOrBackground(), EventCategory.FAILOVER_TRANSITION,
                region, "INDUCED_FAILURE_RESTORED", null, null,
                Map.of("triggeredBy", "simulation", "mechanism", "Database.setDisabled(false)"));
        log.warn("KPI-4 controlled failure simulation: restored {} at {}", region, now);
        simulationState.clear();
        return now;
    }
}
