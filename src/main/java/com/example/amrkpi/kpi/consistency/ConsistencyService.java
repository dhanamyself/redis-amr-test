package com.example.amrkpi.kpi.consistency;

import com.example.amrkpi.config.AmrKpiProperties;
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
import java.util.concurrent.*;

/**
 * KPI 9 — active geo-replication is eventually consistent and resolves concurrent writes via
 * CRDT conflict resolution; for opaque string session blobs that means one concurrent write wins
 * and the other's data is silently gone. These two tests quantify that risk window. The
 * architectural conclusion they inform (see README): prefer region-affinity for session writes —
 * a given session's writes go to one region, the other is failover — which is exactly the
 * behavior the weighted client-side failover in KPI 4 produces.
 */
@Service
public class ConsistencyService {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyService.class);
    private static final int PROBE_KEY_TTL_SECONDS = 300;

    private final AmrEndpoints endpoints;
    private final ProbeClients probeClients;
    private final EventRecorder eventRecorder;
    private final RawEventRepository rawEventRepository;
    private final int defaultConcurrentWriteWindowMillis;
    private final int convergencePollTimeoutMillis;
    private final int pollBackoffMillis = 50;

    public ConsistencyService(AmrEndpoints endpoints, ProbeClients probeClients, EventRecorder eventRecorder,
                               RawEventRepository rawEventRepository, AmrKpiProperties props) {
        this.endpoints = endpoints;
        this.probeClients = probeClients;
        this.eventRecorder = eventRecorder;
        this.rawEventRepository = rawEventRepository;
        this.defaultConcurrentWriteWindowMillis = props.getConsistency().getConcurrentWriteWindowMillis();
        this.convergencePollTimeoutMillis = props.getConsistency().getConvergencePollTimeoutMillis();
    }

    /** Write to one region, immediately read from the other, then track how long it stays stale. */
    public StalenessResult staleness(String writeRegion, String readRegion, String runId) {
        RedisClient writer = probeClients.get(writeRegion);
        RedisClient reader = probeClients.get(readRegion);

        String key = "probe:staleness:" + UUID.randomUUID();
        String value = "v-" + UUID.randomUUID();
        writer.set(key, value, SetParams.setParams().ex(PROBE_KEY_TTL_SECONDS));
        long writeNanos = System.nanoTime();

        String immediateValue = reader.get(key);
        boolean staleAtImmediateRead = !value.equals(immediateValue);

        boolean becameConsistent = staleAtImmediateRead ? pollUntilVisible(reader, key, value) : true;
        long staleWindowMillis = (System.nanoTime() - writeNanos) / 1_000_000;

        String effectiveRunId = runId != null ? runId : Run.BACKGROUND_RUN_ID;
        eventRecorder.record(effectiveRunId, EventCategory.CONSISTENCY_STALENESS,
                writeRegion + "->" + readRegion, staleAtImmediateRead ? "STALE_AT_T0" : "CONSISTENT_AT_T0",
                staleWindowMillis, null,
                Map.of("writeRegion", writeRegion, "readRegion", readRegion, "becameConsistent", becameConsistent));

        cleanup(writer, reader, key);
        return new StalenessResult(writeRegion, readRegion, staleAtImmediateRead, becameConsistent, staleWindowMillis);
    }

    /** Writes different values to the same key in both regions within a configurable window, then observes convergence. */
    public ConflictResult concurrentConflict(String runId, Integer writeGapMillisOverride) throws InterruptedException, ExecutionException {
        int writeGapMillis = writeGapMillisOverride != null ? writeGapMillisOverride : defaultConcurrentWriteWindowMillis;

        String regionA = endpoints.localName();
        String regionB = endpoints.failoverName();
        RedisClient clientA = probeClients.get(regionA);
        RedisClient clientB = probeClients.get(regionB);

        String key = "probe:conflict:" + UUID.randomUUID();
        String valueA = "A-" + UUID.randomUUID();
        String valueB = "B-" + UUID.randomUUID();

        try (ExecutorService executor = Executors.newFixedThreadPool(2, Thread.ofVirtual().factory())) {
            CountDownLatch startLatch = new CountDownLatch(1);
            Future<Long> writeA = executor.submit(() -> writeAt(startLatch, clientA, key, valueA, 0));
            Future<Long> writeB = executor.submit(() -> writeAt(startLatch, clientB, key, valueB,
                    writeGapMillis > 0 ? randomDelayWithinWindow(writeGapMillis) : 0));
            startLatch.countDown();
            long lastWriteNanos = Math.max(writeA.get(), writeB.get());

            long deadline = lastWriteNanos + Duration.ofMillis(convergencePollTimeoutMillis).toNanos();
            String converged = null;
            while (System.nanoTime() < deadline) {
                String a = clientA.get(key);
                String b = clientB.get(key);
                if (a != null && a.equals(b)) {
                    converged = a;
                    break;
                }
                Thread.sleep(pollBackoffMillis);
            }
            long convergenceTimeMillis = (System.nanoTime() - lastWriteNanos) / 1_000_000;

            boolean didConverge = converged != null;
            String survivingRegion = !didConverge ? "DIVERGED"
                    : converged.equals(valueA) ? regionA : converged.equals(valueB) ? regionB : "UNKNOWN";

            String effectiveRunId = runId != null ? runId : Run.BACKGROUND_RUN_ID;
            eventRecorder.record(effectiveRunId, EventCategory.CONSISTENCY_CONFLICT, regionA + "/" + regionB,
                    didConverge ? "CONVERGED" : "DIVERGED_AT_TIMEOUT", convergenceTimeMillis, null,
                    Map.of("writeGapMillis", writeGapMillis, "survivingRegion", survivingRegion));

            cleanup(clientA, clientB, key);
            return new ConflictResult(regionA, regionB, writeGapMillis, didConverge, survivingRegion, convergenceTimeMillis);
        }
    }

    private long writeAt(CountDownLatch startLatch, RedisClient client, String key, String value, long delayMillis) {
        try {
            startLatch.await();
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            client.set(key, value, SetParams.setParams().ex(PROBE_KEY_TTL_SECONDS));
            return System.nanoTime();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static long randomDelayWithinWindow(int boundMillis) {
        return ThreadLocalRandom.current().nextLong(boundMillis + 1);
    }

    private boolean pollUntilVisible(RedisClient reader, String key, String expectedValue) {
        long deadline = System.nanoTime() + Duration.ofMillis(convergencePollTimeoutMillis).toNanos();
        while (System.nanoTime() < deadline) {
            if (expectedValue.equals(reader.get(key))) {
                return true;
            }
            try {
                Thread.sleep(pollBackoffMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("Staleness probe {} did not converge within {}ms", key, convergencePollTimeoutMillis);
        return false;
    }

    private void cleanup(RedisClient a, RedisClient b, String key) {
        try {
            a.del(key);
            b.del(key);
        } catch (Exception ignored) {
        }
    }

    public ConsistencyReport report(Instant from, Instant to) {
        List<RawEvent> stalenessEvents = rawEventRepository.findByCategoryAndTimestampBetweenOrderByTimestampAsc(
                EventCategory.CONSISTENCY_STALENESS, from, to);
        List<RawEvent> conflictEvents = rawEventRepository.findByCategoryAndTimestampBetweenOrderByTimestampAsc(
                EventCategory.CONSISTENCY_CONFLICT, from, to);

        long staleAtT0Count = stalenessEvents.stream().filter(e -> "STALE_AT_T0".equals(e.getOutcome())).count();
        PercentileUtil.Stats staleWindowStats = PercentileUtil.stats(
                stalenessEvents.stream().map(RawEvent::getDurationMillis).filter(java.util.Objects::nonNull).toList());

        long convergedCount = conflictEvents.stream().filter(e -> "CONVERGED".equals(e.getOutcome())).count();
        long divergedCount = conflictEvents.size() - convergedCount;
        PercentileUtil.Stats convergenceStats = PercentileUtil.stats(
                conflictEvents.stream().map(RawEvent::getDurationMillis).filter(java.util.Objects::nonNull).toList());

        return new ConsistencyReport(from, to, stalenessEvents.size(), staleAtT0Count, staleWindowStats,
                conflictEvents.size(), convergedCount, divergedCount, convergenceStats);
    }
}
