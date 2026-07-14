package com.example.amrkpi.metrics;

import com.example.amrkpi.persistence.entity.EventCategory;
import com.example.amrkpi.persistence.entity.RawEvent;
import com.example.amrkpi.persistence.entity.Run;
import com.example.amrkpi.persistence.repository.RawEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Persists every low-frequency raw sample/event (uptime pings, geo-replication probes, failover
 * events, breaker transitions, token renewals, consistency outcomes) — these are rare enough
 * that, unlike load-generator ops, individual timelines matter and every one is kept.
 */
@Component
public class EventRecorder {

    private final RawEventRepository repository;
    private final ObjectMapper objectMapper;

    public EventRecorder(RawEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public RawEvent record(String runId, EventCategory category, String region, String outcome,
                            Long durationMillis, ErrorCategory errorCategory, Map<String, Object> detail) {
        RawEvent event = new RawEvent();
        event.setRunId(runId == null ? Run.BACKGROUND_RUN_ID : runId);
        event.setCategory(category);
        event.setRegion(region);
        event.setTimestamp(Instant.now());
        event.setDurationMillis(durationMillis);
        event.setOutcome(outcome);
        event.setErrorCategory(errorCategory);
        event.setDetailJson(writeJson(detail));
        return repository.save(event);
    }

    private String writeJson(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            return "{\"error\":\"failed to serialize detail: " + e.getMessage() + "\"}";
        }
    }
}
