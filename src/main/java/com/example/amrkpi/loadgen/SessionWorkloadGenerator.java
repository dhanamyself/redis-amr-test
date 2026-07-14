package com.example.amrkpi.loadgen;

import com.example.amrkpi.metrics.ErrorCategory;
import com.example.amrkpi.metrics.OperationRecorder;
import com.example.amrkpi.redis.AmrEndpoints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.MultiDbClient;
import redis.clients.jedis.params.GetExParams;
import redis.clients.jedis.params.SetParams;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * KPI 3 — drives the session workload model (see build spec "Session workload model") against
 * the resilient {@link MultiDbClient}. Supports both closed-loop (N workers back-to-back) and
 * open-loop (paced to a target ops/sec, one submission per virtual thread) modes — closed-loop-
 * only testing understates tail latency under saturation (coordinated omission), so open-loop is
 * a first-class option, not an afterthought.
 */
public class SessionWorkloadGenerator {

    private static final Logger log = LoggerFactory.getLogger(SessionWorkloadGenerator.class);

    public static final String OP_GET = "loadgen.get";
    public static final String OP_SET = "loadgen.set";

    private final MultiDbClient redisClient;
    private final OperationRecorder recorder;
    private final AmrEndpoints endpoints;
    private final String runId;
    private final LoadGenConfig config;
    private final KeyGenerator keyGenerator;
    private final ValueGenerator valueGenerator;

    private final AtomicLong achievedCount = new AtomicLong();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    public SessionWorkloadGenerator(MultiDbClient redisClient, OperationRecorder recorder, AmrEndpoints endpoints,
                                     String runId, LoadGenConfig config) {
        this.redisClient = redisClient;
        this.recorder = recorder;
        this.endpoints = endpoints;
        this.runId = runId;
        this.config = config;
        this.keyGenerator = new KeyGenerator(config.keySpaceSize(), config.hotSetFraction(),
                config.hotSetAccessFraction(), "session:" + runId + ":");
        this.valueGenerator = new ValueGenerator(config.valueSizeMeanBytes(), config.valueSizeMaxBytes());
    }

    public void run() {
        Instant deadline = Instant.now().plusSeconds(config.durationSeconds());
        log.info("Load generator run {} starting: {} for {}s", runId, config, config.durationSeconds());
        if (config.isOpenLoop()) {
            runOpenLoop(deadline);
        } else {
            runClosedLoop(deadline);
        }
        log.info("Load generator run {} finished: {} ops attempted", runId, achievedCount.get());
    }

    public void requestStop() {
        stopRequested.set(true);
    }

    public long achievedCount() {
        return achievedCount.get();
    }

    private void runClosedLoop(Instant deadline) {
        try (ExecutorService executor = Executors.newFixedThreadPool(config.concurrency(), Thread.ofVirtual().factory())) {
            for (int i = 0; i < config.concurrency(); i++) {
                executor.submit(() -> {
                    while (!stopRequested.get() && Instant.now().isBefore(deadline)) {
                        performOneOp();
                    }
                });
            }
        }
    }

    private void runOpenLoop(Instant deadline) {
        long intervalNanos = 1_000_000_000L / config.targetOpsPerSec();
        Semaphore inFlight = new Semaphore(Math.max(config.concurrency(), 1));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            long nextTick = System.nanoTime();
            while (!stopRequested.get() && Instant.now().isBefore(deadline)) {
                long now = System.nanoTime();
                if (now < nextTick) {
                    LockSupport.parkNanos(nextTick - now);
                    continue;
                }
                nextTick += intervalNanos;

                // Bounded in-flight requests: if saturated, this tick's arrival is dropped rather
                // than queued — that gap is exactly the "achieved < target" signal the report
                // surfaces, not something to paper over with an unbounded backlog.
                if (!inFlight.tryAcquire()) {
                    continue;
                }
                executor.submit(() -> {
                    try {
                        performOneOp();
                    } finally {
                        inFlight.release();
                    }
                });
            }
        }
    }

    private void performOneOp() {
        boolean isWrite = ThreadLocalRandom.current().nextDouble() >= config.readWriteRatio();
        String key = keyGenerator.nextKey();
        long start = System.nanoTime();
        try {
            if (isWrite) {
                redisClient.get(key); // read-modify-write: read current session before updating it
                redisClient.set(key, valueGenerator.nextValue(), SetParams.setParams().ex(config.ttlSeconds()));
                recorder.recordSuccess(runId, currentRegion(), OP_SET, System.nanoTime() - start);
            } else {
                String value = config.slidingExpiration()
                        ? redisClient.getEx(key, GetExParams.getExParams().ex(config.ttlSeconds()))
                        : redisClient.get(key);
                if (value == null) {
                    // Session miss (never-seen or expired key) — self-sustaining workload creates it.
                    redisClient.set(key, valueGenerator.nextValue(), SetParams.setParams().ex(config.ttlSeconds()));
                }
                recorder.recordSuccess(runId, currentRegion(), OP_GET, System.nanoTime() - start);
            }
        } catch (Exception e) {
            ErrorCategory category = ErrorCategory.classify(e);
            recorder.recordError(runId, currentRegion(), isWrite ? OP_SET : OP_GET, category);
        } finally {
            achievedCount.incrementAndGet();
        }
    }

    private String currentRegion() {
        try {
            return endpoints.nameOf(redisClient.getActiveDatabaseEndpoint());
        } catch (Exception e) {
            return "n/a";
        }
    }
}
