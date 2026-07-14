package com.example.amrkpi.persistence.entity;

/** Discriminator for RawEvent — the low-frequency, every-sample-persisted event stream. */
public enum EventCategory {
    UPTIME_PROBE,
    GEO_REPLICATION_PROBE,
    FAILOVER_TRANSITION,
    BREAKER_STATE_TRANSITION,
    TOKEN_RENEWAL,
    CONSISTENCY_STALENESS,
    CONSISTENCY_CONFLICT
}
