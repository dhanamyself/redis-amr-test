package com.example.amrkpi.kpi.consistency;

public record ConflictResult(
        String regionA,
        String regionB,
        long writeGapMillis,
        boolean converged,
        String survivingRegion,
        long convergenceTimeMillis
) {
}
