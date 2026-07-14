package com.example.amrkpi.persistence;

import com.example.amrkpi.metrics.ErrorCategory;
import com.example.amrkpi.persistence.entity.*;
import com.example.amrkpi.persistence.repository.MetricRollupRepository;
import com.example.amrkpi.persistence.repository.RawEventRepository;
import com.example.amrkpi.persistence.repository.RunRepository;
import com.example.amrkpi.persistence.repository.SourceOfTruthRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persists (and flushes to the real H2 schema, not just the Hibernate session cache) one
 * instance of every JPA entity in the app. This is exactly the class of test that would have
 * caught the "key" reserved-word DDL failure on source_of_truth before it silently broke KPI 6 —
 * @DataJpaTest builds its schema from the real entity mappings against a real (in-memory) H2
 * database, so a bad column name, a missing not-null default, or an enum mapping mismatch fails
 * here instead of at first real use.
 */
@DataJpaTest
class EntityPersistenceTest {

    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private RunRepository runRepository;
    @Autowired
    private RawEventRepository rawEventRepository;
    @Autowired
    private MetricRollupRepository metricRollupRepository;
    @Autowired
    private SourceOfTruthRepository sourceOfTruthRepository;

    @Test
    void persistsAndReloadsRun() {
        Run run = new Run();
        run.setId(UUID.randomUUID().toString());
        run.setType("loadgen");
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        run.setConfigJson("{\"concurrency\":32}");

        runRepository.saveAndFlush(run);
        entityManager.clear();

        Run reloaded = runRepository.findById(run.getId()).orElseThrow();
        assertThat(reloaded.getType()).isEqualTo("loadgen");
        assertThat(reloaded.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(reloaded.getConfigJson()).contains("concurrency");
    }

    @Test
    void persistsAndReloadsRawEventWithErrorCategoryEnum() {
        RawEvent event = new RawEvent();
        event.setRunId(Run.BACKGROUND_RUN_ID);
        event.setCategory(EventCategory.UPTIME_PROBE);
        event.setRegion("canada-central");
        event.setTimestamp(Instant.now());
        event.setDurationMillis(42L);
        event.setOutcome("DOWN");
        event.setErrorCategory(ErrorCategory.CONNECT_TIMEOUT);
        event.setDetailJson("{\"key\":\"value\"}");

        RawEvent saved = rawEventRepository.saveAndFlush(event);
        entityManager.clear();

        RawEvent reloaded = rawEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getCategory()).isEqualTo(EventCategory.UPTIME_PROBE);
        assertThat(reloaded.getErrorCategory()).isEqualTo(ErrorCategory.CONNECT_TIMEOUT);
        assertThat(reloaded.getRegion()).isEqualTo("canada-central");
    }

    @Test
    void persistsAndReloadsMetricRollup() {
        MetricRollup rollup = new MetricRollup();
        rollup.setRunId(Run.BACKGROUND_RUN_ID);
        rollup.setRegion("canada-central");
        rollup.setOperation("loadgen.get");
        Instant now = Instant.now();
        rollup.setWindowStart(now.minusSeconds(1));
        rollup.setWindowEnd(now);
        rollup.setWarmUp(false);
        rollup.setSuccessCount(1000);
        rollup.setMinMicros(100);
        rollup.setP50Micros(500);
        rollup.setP95Micros(900);
        rollup.setP99Micros(1200);
        rollup.setP999Micros(2000);
        rollup.setMaxMicros(5000);
        rollup.setErrorCountsJson("{\"CONNECT_TIMEOUT\":3}");

        MetricRollup saved = metricRollupRepository.saveAndFlush(rollup);
        entityManager.clear();

        MetricRollup reloaded = metricRollupRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getOperation()).isEqualTo("loadgen.get");
        assertThat(reloaded.getSuccessCount()).isEqualTo(1000);
        assertThat(reloaded.getP999Micros()).isEqualTo(2000);
    }

    @Test
    void persistsAndReloadsSourceOfTruthRecordDespiteReservedWordFieldName() {
        // "key" is a SQL reserved word — this is the exact case that broke table creation
        // before @Column(name = "cache_key") was added. Pinning it here so a future rename of
        // that @Column annotation (accidental or "cleanup") fails loudly in CI.
        SourceOfTruthRecord record = new SourceOfTruthRecord();
        record.setKey("cacheaside:session:123");
        record.setPayload("some-base64-payload");
        record.setUpdatedAt(Instant.now());

        sourceOfTruthRepository.saveAndFlush(record);
        entityManager.clear();

        SourceOfTruthRecord reloaded = sourceOfTruthRepository.findById("cacheaside:session:123").orElseThrow();
        assertThat(reloaded.getPayload()).isEqualTo("some-base64-payload");
    }

    @Test
    void allFourEntityTablesAreIndependentlyQueryable() {
        // A broad sanity check that schema creation succeeded for every table in one shot —
        // if any entity's DDL had failed, at least one of these would throw instead of
        // returning an empty list.
        assertThat(runRepository.findAll()).isInstanceOf(List.class);
        assertThat(rawEventRepository.findAll()).isInstanceOf(List.class);
        assertThat(metricRollupRepository.findAll()).isInstanceOf(List.class);
        assertThat(sourceOfTruthRepository.findAll()).isInstanceOf(List.class);
    }
}
