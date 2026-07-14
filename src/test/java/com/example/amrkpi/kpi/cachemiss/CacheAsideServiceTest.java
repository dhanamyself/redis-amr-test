package com.example.amrkpi.kpi.cachemiss;

import com.example.amrkpi.config.AmrKpiProperties;
import com.example.amrkpi.metrics.ErrorCategory;
import com.example.amrkpi.metrics.OperationRecorder;
import com.example.amrkpi.persistence.repository.MetricRollupRepository;
import com.example.amrkpi.redis.AmrEndpoints;
import com.example.amrkpi.report.RollupAggregator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.MultiDbClient;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.params.SetParams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * KPI 6's whole point is the three-way split between a real cache hit, a miss that populates the
 * cache, and Redis being unavailable entirely (the degraded/cache-less path) — these are
 * distinguishable in the persisted metrics only if the service actually branches correctly, so
 * that branching is worth pinning down directly rather than trusting it from a read-through.
 */
@ExtendWith(MockitoExtension.class)
class CacheAsideServiceTest {

    @Mock
    private MultiDbClient redisClient;
    @Mock
    private SourceOfTruthService sourceOfTruthService;
    @Mock
    private AmrEndpoints endpoints;
    @Mock
    private MetricRollupRepository rollupRepository;

    private OperationRecorder recorder;
    private CacheAsideService service;

    @BeforeEach
    void setUp() {
        recorder = new OperationRecorder(new SimpleMeterRegistry());
        AmrKpiProperties props = new AmrKpiProperties();
        RollupAggregator aggregator = new RollupAggregator(new com.fasterxml.jackson.databind.ObjectMapper());
        service = new CacheAsideService(redisClient, sourceOfTruthService, recorder, endpoints, props,
                rollupRepository, aggregator);
        lenient().when(endpoints.nameOf(any())).thenReturn("canada-central");
    }

    @Test
    void cacheHitReturnsTheRedisValueAndNeverTouchesTheSourceOfTruth() {
        when(redisClient.get("cacheaside:foo")).thenReturn("cached-value");

        String result = service.get("cacheaside:foo", "run-1");

        assertThat(result).isEqualTo("cached-value");
        verifyNoInteractions(sourceOfTruthService);
        verify(redisClient, never()).set(anyString(), anyString(), any(SetParams.class));

        OperationRecorder.Key hitKey = new OperationRecorder.Key("run-1", "canada-central", CacheAsideService.OP_HIT);
        assertThat(recorder.takeIntervalHistogram(hitKey).getTotalCount()).isEqualTo(1);
    }

    @Test
    void cacheMissFetchesFromSourceOfTruthAndPopulatesRedis() {
        when(redisClient.get("cacheaside:foo")).thenReturn(null);
        when(sourceOfTruthService.fetch("cacheaside:foo")).thenReturn("from-source");

        String result = service.get("cacheaside:foo", "run-1");

        assertThat(result).isEqualTo("from-source");
        verify(redisClient).set(eq("cacheaside:foo"), eq("from-source"), any(SetParams.class));

        OperationRecorder.Key missKey = new OperationRecorder.Key("run-1", "canada-central", CacheAsideService.OP_MISS);
        assertThat(recorder.takeIntervalHistogram(missKey).getTotalCount()).isEqualTo(1);
    }

    @Test
    void redisFailureFallsBackToSourceOfTruthWithoutAttemptingToRepopulateCache() {
        when(redisClient.get("cacheaside:foo")).thenThrow(new JedisConnectionException("connection refused"));
        when(sourceOfTruthService.fetch("cacheaside:foo")).thenReturn("degraded-value");

        String result = service.get("cacheaside:foo", "run-1");

        assertThat(result).isEqualTo("degraded-value");
        // Redis is down -> must not attempt to write back to it.
        verify(redisClient, never()).set(anyString(), anyString(), any(SetParams.class));

        OperationRecorder.Key degradedKey = new OperationRecorder.Key("run-1", "canada-central", CacheAsideService.OP_DEGRADED);
        assertThat(recorder.takeAndResetErrorCounts(degradedKey)).containsEntry(ErrorCategory.SOCKET_READ_TIMEOUT, 1L);
        assertThat(recorder.takeIntervalHistogram(degradedKey).getTotalCount()).isEqualTo(1);
    }

    @Test
    void missingRunIdFallsBackToBackgroundTag() {
        when(redisClient.get("cacheaside:foo")).thenReturn("cached-value");

        service.get("cacheaside:foo", null);

        OperationRecorder.Key backgroundHitKey = new OperationRecorder.Key("background", "canada-central", CacheAsideService.OP_HIT);
        assertThat(recorder.takeIntervalHistogram(backgroundHitKey).getTotalCount()).isEqualTo(1);
    }

    @Test
    void regionResolutionFailureDoesNotBreakTheRequest() {
        when(redisClient.get("cacheaside:foo")).thenReturn("cached-value");
        when(redisClient.getActiveDatabaseEndpoint()).thenThrow(new IllegalStateException("no active database"));

        String result = service.get("cacheaside:foo", "run-1");

        assertThat(result).isEqualTo("cached-value");
        OperationRecorder.Key fallbackRegionKey = new OperationRecorder.Key("run-1", "n/a", CacheAsideService.OP_HIT);
        assertThat(recorder.takeIntervalHistogram(fallbackRegionKey).getTotalCount()).isEqualTo(1);
    }
}
