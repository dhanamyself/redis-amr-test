package com.example.amrkpi.kpi.geolatency;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background, always-on geo-replication lag sampling (both directions), tagged "background". */
@Component
public class GeoReplicationScheduler {

    private final GeoReplicationService service;

    public GeoReplicationScheduler(GeoReplicationService service) {
        this.service = service;
    }

    @Scheduled(fixedRateString = "#{${amrkpi.probes.geo-replication-interval-seconds} * 1000}")
    public void probeBothDirections() {
        service.runBothDirections(null);
    }
}
