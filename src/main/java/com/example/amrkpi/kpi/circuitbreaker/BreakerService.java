package com.example.amrkpi.kpi.circuitbreaker;

import com.example.amrkpi.persistence.entity.EventCategory;
import com.example.amrkpi.persistence.entity.RawEvent;
import com.example.amrkpi.persistence.repository.RawEventRepository;
import com.example.amrkpi.redis.CircuitBreakerStatusService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KPI 5 — live breaker state per endpoint plus time-in-state reporting, reconstructed from every
 * persisted BREAKER_STATE_TRANSITION event (registered once at startup via resilience4j's own
 * state-transition event publisher — see AmrRedisClientConfig).
 */
@Service
public class BreakerService {

    private final CircuitBreakerStatusService statusService;
    private final RawEventRepository rawEventRepository;

    public BreakerService(CircuitBreakerStatusService statusService, RawEventRepository rawEventRepository) {
        this.statusService = statusService;
        this.rawEventRepository = rawEventRepository;
    }

    public Map<String, String> currentStates() {
        return statusService.currentStates();
    }

    public BreakerTimeInStateReport report(String region, Instant from, Instant to) {
        List<RawEvent> events = rawEventRepository.findByCategoryAndRegionAndTimestampBetweenOrderByTimestampAsc(
                EventCategory.BREAKER_STATE_TRANSITION, region, from, to);

        Map<String, Long> timeInState = new LinkedHashMap<>();
        for (int i = 0; i < events.size(); i++) {
            RawEvent current = events.get(i);
            Instant intervalEnd = (i + 1 < events.size()) ? events.get(i + 1).getTimestamp() : to;
            long millis = Math.max(Duration.between(current.getTimestamp(), intervalEnd).toMillis(), 0);
            timeInState.merge(current.getOutcome(), millis, Long::sum);
        }

        return new BreakerTimeInStateReport(region, from, to, statusService.stateOf(region), timeInState, events.size());
    }
}
