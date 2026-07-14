package com.example.amrkpi.loadgen;

import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Opaque serialized session blobs, log-normal size distribution centered on the configured mean,
 * capped at the configured max — matches how live session payload sizes actually distribute
 * (long tail of larger sessions), not a fixed size.
 */
public class ValueGenerator {

    private static final double SIGMA = 0.5;
    private static final int MIN_BYTES = 64;

    private final double mu;
    private final int maxBytes;

    public ValueGenerator(int meanBytes, int maxBytes) {
        this.mu = Math.log(Math.max(meanBytes, MIN_BYTES)) - (SIGMA * SIGMA) / 2.0;
        this.maxBytes = maxBytes;
    }

    public String nextValue() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double sampled = Math.exp(mu + SIGMA * random.nextGaussian());
        int size = (int) Math.min(Math.max(sampled, MIN_BYTES), maxBytes);
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
