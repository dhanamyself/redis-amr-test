package com.example.amrkpi.redis;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks whether a KPI-4 controlled failure simulation is currently in progress, and under which
 * run ID, so the database-switch and circuit-breaker listeners (registered once at startup, far
 * from any per-request context) can tag the events they record with the right run — falling back
 * to the "background" run ID for spontaneous transitions outside of any induced test.
 */
@Component
public class FailoverSimulationState {

    private final AtomicReference<String> activeRunId = new AtomicReference<>();

    public void start(String runId) {
        activeRunId.set(runId);
    }

    public void clear() {
        activeRunId.set(null);
    }

    public String currentRunIdOrBackground() {
        String id = activeRunId.get();
        return id != null ? id : com.example.amrkpi.persistence.entity.Run.BACKGROUND_RUN_ID;
    }
}
