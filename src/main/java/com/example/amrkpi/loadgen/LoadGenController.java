package com.example.amrkpi.loadgen;

import com.example.amrkpi.config.AmrKpiProperties;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/loadgen")
public class LoadGenController {

    private final LoadGenService loadGenService;
    private final AmrKpiProperties props;

    public LoadGenController(LoadGenService loadGenService, AmrKpiProperties props) {
        this.loadGenService = loadGenService;
        this.props = props;
    }

    /** Every field optional — unset fields fall back to amrkpi.workload.* defaults from application.yml. */
    public record StartRequest(
            Integer durationSeconds,
            Integer concurrency,
            Integer targetOpsPerSec,
            Double readWriteRatio,
            Integer keySpaceSize,
            Integer valueSizeMeanBytes,
            Integer valueSizeMaxBytes,
            Double hotSetFraction,
            Double hotSetAccessFraction,
            Integer ttlSeconds,
            Boolean slidingExpiration,
            Integer warmUpSeconds
    ) {
    }

    public record StartResponse(String runId) {
    }

    @PostMapping("/start")
    public StartResponse start(@RequestBody(required = false) StartRequest request) {
        LoadGenConfig config = resolveConfig(request);
        String runId = loadGenService.start(config);
        return new StartResponse(runId);
    }

    @GetMapping("/status")
    public LoadGenStatus status() {
        return loadGenService.status();
    }

    @PostMapping("/stop")
    public void stop() {
        loadGenService.stop();
    }

    private LoadGenConfig resolveConfig(StartRequest r) {
        AmrKpiProperties.Workload w = props.getWorkload();
        if (r == null) {
            r = new StartRequest(null, null, null, null, null, null, null, null, null, null, null, null);
        }
        return new LoadGenConfig(
                orDefault(r.durationSeconds(), 900),
                orDefault(r.concurrency(), 32),
                orDefault(r.targetOpsPerSec(), 0),
                orDefault(r.readWriteRatio(), w.getReadWriteRatio()),
                orDefault(r.keySpaceSize(), w.getDefaultKeySpaceSize()),
                orDefault(r.valueSizeMeanBytes(), w.getValueSizeMeanBytes()),
                orDefault(r.valueSizeMaxBytes(), w.getValueSizeMaxBytes()),
                orDefault(r.hotSetFraction(), w.getHotSetFraction()),
                orDefault(r.hotSetAccessFraction(), w.getHotSetAccessFraction()),
                orDefault(r.ttlSeconds(), w.getTtlSeconds()),
                r.slidingExpiration() != null ? r.slidingExpiration() : w.isSlidingExpiration(),
                orDefault(r.warmUpSeconds(), w.getWarmUpSeconds())
        );
    }

    private int orDefault(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    private double orDefault(Double value, double fallback) {
        return value != null ? value : fallback;
    }
}
