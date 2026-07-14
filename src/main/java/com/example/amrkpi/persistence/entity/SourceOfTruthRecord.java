package com.example.amrkpi.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Stand-in for a real backing datastore behind the cache-aside path (KPI 6). This is NOT part of
 * the AMR validation itself — it exists purely so the harness can demonstrate a genuine
 * GET-miss-populate cycle and the "degraded mode" (cache-less) latency comparison without a real
 * downstream dependency. See README.
 */
@Entity
@Table(name = "source_of_truth")
@Getter
@Setter
@NoArgsConstructor
public class SourceOfTruthRecord {

    @Id
    @Column(name = "cache_key")
    private String key;

    @Lob
    private String payload;

    private Instant updatedAt;
}
