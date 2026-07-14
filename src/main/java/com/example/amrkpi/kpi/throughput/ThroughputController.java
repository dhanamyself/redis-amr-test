package com.example.amrkpi.kpi.throughput;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for KPI 3 (throughput). The load generator itself is controlled via
 * {@code LoadGenController} (see {@code POST /loadgen/start}); this endpoint reports on a
 * completed or in-progress run's rollups once it has a {@code runId}. Sequence diagram:
 * {@code docs/ARCHITECTURE.md § 3}.
 */
@RestController
public class ThroughputController {

    private final ThroughputService service;

    public ThroughputController(ThroughputService service) {
        this.service = service;
    }

    /**
     * Achieved-vs-target ops/sec, latency percentiles through p99.9, and error breakdown for one
     * load-test run, split by operation type and region, with warm-up excluded.
     *
     * @param runId the run ID returned by {@code POST /loadgen/start}
     * @return the full throughput report for that run
     */
    @GetMapping("/kpi/throughput/{runId}")
    public ThroughputReport report(@PathVariable String runId) {
        return service.report(runId);
    }
}
