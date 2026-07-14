package com.example.amrkpi.kpi.tokenlifecycle;

import com.example.amrkpi.persistence.entity.EventCategory;
import com.example.amrkpi.persistence.entity.RawEvent;
import com.example.amrkpi.persistence.repository.MetricRollupRepository;
import com.example.amrkpi.persistence.repository.RawEventRepository;
import com.example.amrkpi.report.RollupAggregator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * KPI 8 — correlates each Entra ID token renewal instant against the concurrent load test's
 * rollups, to answer: does token renewal under sustained load cause any error blip or latency
 * spike on pooled connections? redis-authx-entraid's AuthXManager re-authenticates pooled/idle
 * connections in place on renewal (verified from source — see AmrAuthConfig javadoc); this is
 * what actually proves that's seamless under load.
 */
@Service
public class TokenLifecycleService {

    private static final int DEFAULT_CORRELATION_WINDOW_SECONDS = 10;

    private final RawEventRepository rawEventRepository;
    private final MetricRollupRepository rollupRepository;
    private final RollupAggregator aggregator;
    private final ObjectMapper objectMapper;

    public TokenLifecycleService(RawEventRepository rawEventRepository, MetricRollupRepository rollupRepository,
                                  RollupAggregator aggregator, ObjectMapper objectMapper) {
        this.rawEventRepository = rawEventRepository;
        this.rollupRepository = rollupRepository;
        this.aggregator = aggregator;
        this.objectMapper = objectMapper;
    }

    public TokenLifecycleReport report(String correlationRunId, Instant from, Instant to, Integer windowSeconds) {
        int window = windowSeconds != null ? windowSeconds : DEFAULT_CORRELATION_WINDOW_SECONDS;
        List<RawEvent> events = rawEventRepository.findByCategoryAndTimestampBetweenOrderByTimestampAsc(
                EventCategory.TOKEN_RENEWAL, from, to);

        long successCount = events.stream().filter(e -> "SUCCESS".equals(e.getOutcome())).count();
        long failureCount = events.size() - successCount;

        List<TokenRenewalCorrelation> renewals = events.stream()
                .map(e -> toCorrelation(e, correlationRunId, window))
                .toList();

        return new TokenLifecycleReport(from, to, successCount, failureCount, renewals);
    }

    private TokenRenewalCorrelation toCorrelation(RawEvent event, String correlationRunId, int windowSeconds) {
        Map<String, Object> detail = readDetail(event.getDetailJson());
        String user = String.valueOf(detail.getOrDefault("user", null));
        Instant expiresAt = parseInstant(detail.get("expiresAt"));
        Long ttlMillis = detail.get("ttlMillis") != null ? ((Number) detail.get("ttlMillis")).longValue() : null;

        var windowStats = correlationRunId == null ? null : aggregator.aggregate(
                rollupRepository.findByRunIdOrderByWindowStartAsc(correlationRunId).stream()
                        .filter(r -> !r.getWindowStart().isBefore(event.getTimestamp().minusSeconds(windowSeconds))
                                && !r.getWindowStart().isAfter(event.getTimestamp().plusSeconds(windowSeconds)))
                        .toList(),
                false);

        return new TokenRenewalCorrelation(event.getTimestamp(), event.getOutcome(), user, expiresAt, ttlMillis, windowStats);
    }

    private Map<String, Object> readDetail(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Instant parseInstant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
