package com.example.amrkpi.persistence.repository;

import com.example.amrkpi.persistence.entity.MetricRollup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface MetricRollupRepository extends JpaRepository<MetricRollup, Long> {

    List<MetricRollup> findByOperationAndWindowStartBetweenOrderByWindowStartAsc(
            String operation, Instant from, Instant to);

    List<MetricRollup> findByRunIdAndOperationOrderByWindowStartAsc(String runId, String operation);

    List<MetricRollup> findByRunIdOrderByWindowStartAsc(String runId);

    List<MetricRollup> findByOperationAndRegionAndWindowStartBetweenOrderByWindowStartAsc(
            String operation, String region, Instant from, Instant to);
}
