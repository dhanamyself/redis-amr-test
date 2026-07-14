package com.example.amrkpi.kpi.cachemiss;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REST surface for KPI 6 (cache-aside / read-miss). Exercises a real GET-miss-populate cycle
 * against AMR via {@link CacheAsideService}, backed by an H2 source-of-truth stand-in on miss —
 * see {@link SourceOfTruthService}. See the sequence diagram at
 * {@code docs/ARCHITECTURE.md § 4} for the hit/miss/degraded branch behavior in full.
 */
@RestController
@RequestMapping("/kpi/cache-aside")
public class CacheAsideController {

    private final CacheAsideService service;

    public CacheAsideController(CacheAsideService service) {
        this.service = service;
    }

    /**
     * Performs one cache-aside GET (key is prefixed with {@code cacheaside:}), for manual testing
     * or demos. Classifies as a hit, a miss (populates Redis with TTL from the source-of-truth
     * stand-in), or degraded (Redis unavailable — falls back to the source of truth without
     * repopulating).
     *
     * @param key   the logical session key, without the {@code cacheaside:} prefix
     * @param runId optional run ID to tag the recorded metrics with; defaults to "background"
     * @return the session value, from cache, the source of truth, or the degraded fallback
     */
    @GetMapping("/get")
    public String get(@RequestParam String key, @RequestParam(required = false) String runId) {
        return service.get("cacheaside:" + key, runId);
    }

    public record RunRequest(Integer keySpaceSize, Integer requestCount, Integer concurrency, String runId) {
    }

    public record RunResult(int requestCount, int keySpaceSize, long tookMillis) {
    }

    /**
     * Drives a burst of cache-aside GETs across a bounded key space (virtual-thread concurrency)
     * to populate the report with a realistic hit/miss mix, rather than requiring manual repeated
     * calls to {@link #get}.
     *
     * @param request optional tuning: key-space size (default 500), request count (default 2000),
     *                concurrency (default 16), and an optional run ID to tag results with
     * @return how many requests ran, over what key space, and how long it took
     */
    @PostMapping("/run")
    public RunResult run(@RequestBody(required = false) RunRequest request) {
        int keySpaceSize = (request != null && request.keySpaceSize() != null) ? request.keySpaceSize() : 500;
        int requestCount = (request != null && request.requestCount() != null) ? request.requestCount() : 2000;
        int concurrency = (request != null && request.concurrency() != null) ? request.concurrency() : 16;
        String runId = request != null ? request.runId() : null;

        long start = System.currentTimeMillis();
        List<String> keys = java.util.stream.IntStream.range(0, keySpaceSize)
                .mapToObj(i -> "run:" + i)
                .toList();

        try (ExecutorService executor = Executors.newFixedThreadPool(concurrency, Thread.ofVirtual().factory())) {
            AtomicLong submitted = new AtomicLong();
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                String key = keys.get(ThreadLocalRandom.current().nextInt(keys.size()));
                futures.add(executor.submit(() -> {
                    service.get("cacheaside:" + key, runId);
                    submitted.incrementAndGet();
                }));
            }
            for (var f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                }
            }
        }

        return new RunResult(requestCount, keySpaceSize, System.currentTimeMillis() - start);
    }

    /**
     * Aggregates hit/miss/degraded counts, miss ratio, and per-branch latency stats over a time
     * window, via {@link com.example.amrkpi.report.RollupAggregator}.
     *
     * @param from window start; defaults to one hour before {@code to}
     * @param to   window end; defaults to now
     * @return hit/miss/degraded counts, miss ratio, and latency stats for each branch
     */
    @GetMapping("/report")
    public CacheAsideReport report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(1, ChronoUnit.HOURS);
        return service.report(effectiveFrom, effectiveTo);
    }
}
