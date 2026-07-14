package com.example.amrkpi.kpi.throughput;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ThroughputController {

    private final ThroughputService service;

    public ThroughputController(ThroughputService service) {
        this.service = service;
    }

    @GetMapping("/kpi/throughput/{runId}")
    public ThroughputReport report(@PathVariable String runId) {
        return service.report(runId);
    }
}
