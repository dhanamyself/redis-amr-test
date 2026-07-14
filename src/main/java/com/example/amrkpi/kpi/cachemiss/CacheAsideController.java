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

@RestController
@RequestMapping("/kpi/cache-aside")
public class CacheAsideController {

    private final CacheAsideService service;

    public CacheAsideController(CacheAsideService service) {
        this.service = service;
    }

    @GetMapping("/get")
    public String get(@RequestParam String key, @RequestParam(required = false) String runId) {
        return service.get("cacheaside:" + key, runId);
    }

    public record RunRequest(Integer keySpaceSize, Integer requestCount, Integer concurrency, String runId) {
    }

    public record RunResult(int requestCount, int keySpaceSize, long tookMillis) {
    }

    /** Drives a burst of cache-aside GETs across a bounded key space to populate the report. */
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

    @GetMapping("/report")
    public CacheAsideReport report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(1, ChronoUnit.HOURS);
        return service.report(effectiveFrom, effectiveTo);
    }
}
