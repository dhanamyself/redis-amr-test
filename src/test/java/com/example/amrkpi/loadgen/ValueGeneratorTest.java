package com.example.amrkpi.loadgen;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session payloads are log-normal sized and Base64-encoded before being written to Redis — the
 * string length Jackson/Jedis actually sees isn't the byte size, so these tests decode back to
 * bytes before checking bounds, which is exactly the kind of off-by-encoding mistake that's easy
 * to get wrong when writing (or reviewing) this code by eye.
 */
class ValueGeneratorTest {

    private int decodedByteLength(String base64Value) {
        return Base64.getDecoder().decode(base64Value).length;
    }

    @Test
    void generatedValuesStayWithinConfiguredMinAndMaxBytes() {
        ValueGenerator generator = new ValueGenerator(2048, 20_480);

        for (int i = 0; i < 500; i++) {
            int size = decodedByteLength(generator.nextValue());
            assertThat(size).isBetween(64, 20_480);
        }
    }

    @Test
    void valuesAreClampedToASmallConfiguredMax() {
        ValueGenerator generator = new ValueGenerator(2048, 100);

        for (int i = 0; i < 200; i++) {
            int size = decodedByteLength(generator.nextValue());
            assertThat(size).isLessThanOrEqualTo(100);
        }
    }

    @Test
    void sizesVaryAcrossSamplesRatherThanBeingConstant() {
        ValueGenerator generator = new ValueGenerator(2048, 20_480);

        Set<Integer> distinctSizes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            distinctSizes.add(decodedByteLength(generator.nextValue()));
        }

        assertThat(distinctSizes.size()).isGreaterThan(1);
    }

    @Test
    void averageSizeOverManySamplesIsInTheNeighborhoodOfTheConfiguredMean() {
        ValueGenerator generator = new ValueGenerator(2048, 20_480);

        long total = 0;
        int samples = 3000;
        for (int i = 0; i < samples; i++) {
            total += decodedByteLength(generator.nextValue());
        }
        double average = (double) total / samples;

        // Log-normal with sigma=0.5 has real spread; this is a sanity band; not a precise
        // statistical assertion, but wildly-off output (e.g. always returning the max, or the
        // min) would fail it.
        assertThat(average).isBetween(1200.0, 3500.0);
    }
}
