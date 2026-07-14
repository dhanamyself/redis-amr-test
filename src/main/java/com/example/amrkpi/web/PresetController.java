package com.example.amrkpi.web;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One-click test presets — see {@link PresetService} and the README's "Test presets" table for
 * what each one actually does. Each returns immediately with a run ID (or null for an idle
 * consistency-probe run); long-running orchestration continues on a background virtual thread, so
 * poll {@code GET /loadgen/status} and the relevant KPI report endpoints for progress exactly as
 * with a manually-started run.
 */
@RestController
@RequestMapping("/presets")
public class PresetController {

    private final PresetService presetService;

    public PresetController(PresetService presetService) {
        this.presetService = presetService;
    }

    /**
     * Runs the named preset.
     *
     * @param name one of {@code smoke}, {@code session-peak}, {@code failover-under-load},
     *             {@code soak}, {@code consistency-probe}
     * @return the preset name, its run ID (if it started one), and a human-readable note on what
     *         it did and where to check results
     * @throws IllegalArgumentException if {@code name} doesn't match a known preset
     */
    @PostMapping("/{name}/run")
    public PresetService.PresetResult run(@PathVariable String name) {
        return switch (name) {
            case "smoke" -> presetService.smoke();
            case "session-peak" -> presetService.sessionPeak();
            case "failover-under-load" -> presetService.failoverUnderLoad();
            case "soak" -> presetService.soak();
            case "consistency-probe" -> presetService.consistencyProbe();
            default -> throw new IllegalArgumentException(
                    "Unknown preset: " + name + " (expected one of: smoke, session-peak, failover-under-load, soak, consistency-probe)");
        };
    }
}
