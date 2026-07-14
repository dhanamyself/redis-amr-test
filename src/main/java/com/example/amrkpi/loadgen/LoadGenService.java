package com.example.amrkpi.loadgen;

import com.example.amrkpi.metrics.OperationRecorder;
import com.example.amrkpi.metrics.RunService;
import com.example.amrkpi.persistence.entity.RunStatus;
import com.example.amrkpi.redis.AmrEndpoints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.MultiDbClient;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the load generator's lifecycle. The harness runs at most one load test at a time — this
 * is a measurement tool, not a multi-tenant traffic generator, and one active run keeps
 * "achieved vs target" and the report's rollup queries unambiguous.
 */
@Service
public class LoadGenService {

    private static final Logger log = LoggerFactory.getLogger(LoadGenService.class);

    private final MultiDbClient redisClient;
    private final OperationRecorder recorder;
    private final AmrEndpoints endpoints;
    private final RunService runService;

    private final AtomicReference<ActiveRun> activeRun = new AtomicReference<>();

    public LoadGenService(MultiDbClient redisClient, OperationRecorder recorder, AmrEndpoints endpoints, RunService runService) {
        this.redisClient = redisClient;
        this.recorder = recorder;
        this.endpoints = endpoints;
        this.runService = runService;
    }

    private record ActiveRun(String runId, LoadGenConfig config, SessionWorkloadGenerator generator, Instant startedAt) {
    }

    public synchronized String start(LoadGenConfig config) {
        if (activeRun.get() != null) {
            throw new IllegalStateException(
                    "A load test is already running (runId=" + activeRun.get().runId() + "). Stop it first.");
        }
        String runId = runService.startRun("loadgen", config.warmUpSeconds(), config);
        SessionWorkloadGenerator generator = new SessionWorkloadGenerator(redisClient, recorder, endpoints, runId, config);
        ActiveRun run = new ActiveRun(runId, config, generator, Instant.now());
        activeRun.set(run);

        Thread.ofVirtual().name("loadgen-" + runId).start(() -> {
            RunStatus finalStatus = RunStatus.COMPLETED;
            try {
                generator.run();
            } catch (Exception e) {
                log.error("Load generator run {} failed", runId, e);
                finalStatus = RunStatus.FAILED;
            } finally {
                runService.endRun(runId, finalStatus);
                activeRun.compareAndSet(run, null);
            }
        });

        return runId;
    }

    public synchronized void stop() {
        ActiveRun run = activeRun.get();
        if (run == null) {
            throw new IllegalStateException("No load test is currently running.");
        }
        run.generator().requestStop();
    }

    public LoadGenStatus status() {
        ActiveRun run = activeRun.get();
        if (run == null) {
            return LoadGenStatus.idle();
        }
        long achieved = run.generator().achievedCount();
        double elapsedSeconds = Math.max(Duration.between(run.startedAt(), Instant.now()).toMillis() / 1000.0, 0.001);
        return new LoadGenStatus(true, run.runId(), run.config(), run.startedAt(), achieved, achieved / elapsedSeconds);
    }
}
