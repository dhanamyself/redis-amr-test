package com.example.amrkpi.redis;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.stereotype.Service;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.mcf.MultiDbConnectionProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live circuit-breaker / health status per endpoint (KPI 5), and the enable/disable control used
 * by KPI 4's controlled failure simulation. Both are reached through the retained
 * {@link MultiDbConnectionProvider} bean — {@code MultiDbClient} itself only exposes a boolean
 * {@code isHealthy(Endpoint)}, not the underlying breaker state (verified against Jedis 7.5.3
 * source; see AmrRedisClientConfig javadoc).
 * <p>
 * Note: Jedis disables resilience4j's automatic OPEN-to-HALF_OPEN transition for this client
 * (state changes are driven by Jedis's own health-check/failback logic instead), so HALF_OPEN
 * will not appear from automatic transitions — only CLOSED, OPEN, and FORCED_OPEN are expected
 * in practice.
 */
@Service
public class CircuitBreakerStatusService {

    private final MultiDbConnectionProvider provider;
    private final AmrEndpoints endpoints;

    public CircuitBreakerStatusService(MultiDbConnectionProvider provider, AmrEndpoints endpoints) {
        this.provider = provider;
        this.endpoints = endpoints;
    }

    public Map<String, String> currentStates() {
        Map<String, String> states = new LinkedHashMap<>();
        states.put(endpoints.localName(), stateOf(endpoints.local()));
        states.put(endpoints.failoverName(), stateOf(endpoints.failover()));
        return states;
    }

    public String stateOf(String regionName) {
        return stateOf(endpoints.byName(regionName));
    }

    private String stateOf(HostAndPort endpoint) {
        CircuitBreaker breaker = provider.getDatabase(endpoint).getCircuitBreaker();
        return breaker.getState().name();
    }

    public boolean isHealthy(String regionName) {
        return provider.isHealthy(endpoints.byName(regionName));
    }

    /** KPI 4 controlled failure simulation: force a database unhealthy / restore it. */
    public void setDisabled(String regionName, boolean disabled) {
        provider.getDatabase(endpoints.byName(regionName)).setDisabled(disabled);
    }

    public boolean isDisabled(String regionName) {
        return provider.getDatabase(endpoints.byName(regionName)).isDisabled();
    }
}
