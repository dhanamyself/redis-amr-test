package com.example.amrkpi.kpi.cachemiss;

import com.example.amrkpi.config.AmrKpiProperties;
import com.example.amrkpi.persistence.entity.SourceOfTruthRecord;
import com.example.amrkpi.persistence.repository.SourceOfTruthRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * H2-backed stand-in for whatever real system of record would sit behind the cache in
 * production. Adds a small simulated fetch latency so the cache-aside miss cost and the
 * degraded-mode ("cache-less") cost are actually visible in the report rather than lost in H2's
 * near-zero local latency.
 */
@Service
public class SourceOfTruthService {

    private final SourceOfTruthRepository repository;
    private final int simulatedLatencyMillis;
    private final int valueSizeBytes;

    public SourceOfTruthService(SourceOfTruthRepository repository, AmrKpiProperties props) {
        this.repository = repository;
        this.simulatedLatencyMillis = props.getCacheAside().getSourceOfTruthSimulatedLatencyMillis();
        this.valueSizeBytes = props.getCacheAside().getValueSizeBytes();
    }

    public String fetch(String key) {
        simulateLatency();
        return repository.findById(key)
                .map(SourceOfTruthRecord::getPayload)
                .orElseGet(() -> generateAndPersist(key));
    }

    private String generateAndPersist(String key) {
        String payload = randomPayload(valueSizeBytes);
        SourceOfTruthRecord record = new SourceOfTruthRecord();
        record.setKey(key);
        record.setPayload(payload);
        record.setUpdatedAt(Instant.now());
        repository.save(record);
        return payload;
    }

    private void simulateLatency() {
        if (simulatedLatencyMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(simulatedLatencyMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String randomPayload(int sizeBytes) {
        byte[] bytes = new byte[Math.max(sizeBytes, 1)];
        ThreadLocalRandom.current().nextBytes(bytes);
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }
}
