package com.example.amrkpi.loadgen;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Simple 80/20-style hot-key distribution over the configured key space: a hot subset
 * (hotSetFraction of all keys) receives hotSetAccessFraction of accesses; the rest of the key
 * space shares the remainder. Live sessions are heavily skewed and hot keys behave differently
 * under clustering, so uniform-random GET/SET is not an acceptable stand-in (per build spec).
 */
public class KeyGenerator {

    private final int keySpaceSize;
    private final int hotSetSize;
    private final double hotSetAccessFraction;
    private final String prefix;

    public KeyGenerator(int keySpaceSize, double hotSetFraction, double hotSetAccessFraction, String prefix) {
        this.keySpaceSize = Math.max(keySpaceSize, 1);
        this.hotSetSize = Math.max(1, (int) Math.round(this.keySpaceSize * hotSetFraction));
        this.hotSetAccessFraction = hotSetAccessFraction;
        this.prefix = prefix;
    }

    public String nextKey() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int index;
        if (random.nextDouble() < hotSetAccessFraction) {
            index = random.nextInt(hotSetSize);
        } else {
            index = hotSetSize + random.nextInt(Math.max(keySpaceSize - hotSetSize, 1));
        }
        return prefix + (index % keySpaceSize);
    }
}
