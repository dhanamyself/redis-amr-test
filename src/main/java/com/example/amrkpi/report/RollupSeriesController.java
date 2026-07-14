package com.example.amrkpi.report;

import com.example.amrkpi.persistence.entity.MetricRollup;
import com.example.amrkpi.persistence.repository.MetricRollupRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** Raw rollup time series for dashboard charts (network time, throughput). */
@RestController
public class RollupSeriesController {

    private final MetricRollupRepository rollupRepository;
    private final ObjectMapper objectMapper;

    public RollupSeriesController(MetricRollupRepository rollupRepository, ObjectMapper objectMapper) {
        this.rollupRepository = rollupRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/report/series")
    public List<RollupPoint> series(
            @RequestParam String operation,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(10, ChronoUnit.MINUTES);

        List<MetricRollup> rollups;
        if (runId != null) {
            rollups = rollupRepository.findByRunIdAndOperationOrderByWindowStartAsc(runId, operation);
        } else if (region != null) {
            rollups = rollupRepository.findByOperationAndRegionAndWindowStartBetweenOrderByWindowStartAsc(
                    operation, region, effectiveFrom, effectiveTo);
        } else {
            rollups = rollupRepository.findByOperationAndWindowStartBetweenOrderByWindowStartAsc(
                    operation, effectiveFrom, effectiveTo);
        }

        return rollups.stream()
                .filter(r -> region == null || region.equals(r.getRegion()))
                .filter(r -> !r.getWindowStart().isBefore(effectiveFrom) && !r.getWindowStart().isAfter(effectiveTo))
                .map(r -> new RollupPoint(r.getWindowStart(), r.getSuccessCount(), r.getP50Micros(), r.getP95Micros(),
                        r.getP99Micros(), errorTotal(r.getErrorCountsJson())))
                .toList();
    }

    private long errorTotal(String json) {
        if (json == null || json.isBlank()) {
            return 0;
        }
        try {
            Map<String, Long> errors = objectMapper.readValue(json, new TypeReference<Map<String, Long>>() {
            });
            return errors.values().stream().mapToLong(Long::longValue).sum();
        } catch (Exception e) {
            return 0;
        }
    }
}
