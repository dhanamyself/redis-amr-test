package com.example.amrkpi.metrics;

import com.example.amrkpi.persistence.entity.Run;
import com.example.amrkpi.persistence.entity.RunStatus;
import com.example.amrkpi.persistence.repository.RunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns Run lifecycle: every load test gets a run ID; every rollup/event row is tagged with it.
 * Also tracks each active run's warm-up window in memory so the rollup snapshotter can mark
 * warm-up rollups without a DB round trip per interval.
 */
@Service
public class RunService {

    private final RunRepository runRepository;
    private final OperationRecorder operationRecorder;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, RunHandle> activeRuns = new ConcurrentHashMap<>();

    public RunService(RunRepository runRepository, OperationRecorder operationRecorder, ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.operationRecorder = operationRecorder;
        this.objectMapper = objectMapper;
    }

    private record RunHandle(Instant startedAt, int warmUpSeconds) {
        boolean isWarmUp(Instant at) {
            return at.isBefore(startedAt.plusSeconds(warmUpSeconds));
        }
    }

    public String startRun(String type, int warmUpSeconds, Object effectiveConfig) {
        String id = UUID.randomUUID().toString();
        Run run = new Run();
        run.setId(id);
        run.setType(type);
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        run.setConfigJson(writeJson(effectiveConfig));
        runRepository.save(run);
        activeRuns.put(id, new RunHandle(run.getStartedAt(), warmUpSeconds));
        return id;
    }

    public void endRun(String runId, RunStatus finalStatus) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus(finalStatus);
            run.setEndedAt(Instant.now());
            runRepository.save(run);
        });
        activeRuns.remove(runId);
        operationRecorder.markRunEnded(runId);
    }

    public boolean isWithinWarmUp(String runId, Instant at) {
        RunHandle h = activeRuns.get(runId);
        return h != null && h.isWarmUp(at);
    }

    public boolean isActive(String runId) {
        return activeRuns.containsKey(runId);
    }

    public Optional<Run> get(String runId) {
        return runRepository.findById(runId);
    }

    public List<Run> recent() {
        return runRepository.findTop50ByOrderByStartedAtDesc();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"failed to serialize run config: " + e.getMessage() + "\"}";
        }
    }
}
