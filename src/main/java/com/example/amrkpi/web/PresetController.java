package com.example.amrkpi.web;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/presets")
public class PresetController {

    private final PresetService presetService;

    public PresetController(PresetService presetService) {
        this.presetService = presetService;
    }

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
