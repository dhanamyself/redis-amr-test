package com.example.amrkpi.kpi.consistency;

public record StalenessResult(
        String writeRegion,
        String readRegion,
        boolean staleAtImmediateRead,
        boolean becameConsistent,
        long staleWindowMillis
) {
}
