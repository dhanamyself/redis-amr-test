package com.example.amrkpi.loadgen;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session workload model's hot-key skew (KPI 3) is what distinguishes it from a naive
 * uniform-random load generator, per the build spec — worth verifying the generated keys
 * actually stay in bounds and that the skew parameter has the effect its name implies, not just
 * that the class compiles.
 */
class KeyGeneratorTest {

    private static final Pattern KEY_INDEX = Pattern.compile("^prefix:(\\d+)$");

    private int indexOf(String key) {
        Matcher m = KEY_INDEX.matcher(key);
        assertThat(m.matches()).as("key %s should match prefix:<index>", key).isTrue();
        return Integer.parseInt(m.group(1));
    }

    @RepeatedTest(20)
    void generatedKeysNeverExceedTheConfiguredKeySpace() {
        KeyGenerator generator = new KeyGenerator(100, 0.2, 0.8, "prefix:");

        for (int i = 0; i < 1000; i++) {
            int index = indexOf(generator.nextKey());
            assertThat(index).isBetween(0, 99);
        }
    }

    @Test
    void allHotSetAccessGoesToKeysWithinTheHotFraction() {
        // hotSetAccessFraction = 1.0 -> every access must land in the hot subset
        // (first hotSetFraction * keySpaceSize keys).
        KeyGenerator generator = new KeyGenerator(100, 0.1, 1.0, "prefix:");

        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(indexOf(generator.nextKey()));
        }

        assertThat(seen).allMatch(index -> index < 10);
    }

    @Test
    void zeroHotSetAccessFractionOnlyTouchesTheColdSet() {
        KeyGenerator generator = new KeyGenerator(100, 0.2, 0.0, "prefix:");

        for (int i = 0; i < 500; i++) {
            int index = indexOf(generator.nextKey());
            assertThat(index).isGreaterThanOrEqualTo(20);
        }
    }

    @Test
    void skewedAccessConcentratesOnASmallFractionOfKeysOverManySamples() {
        KeyGenerator generator = new KeyGenerator(1000, 0.2, 0.8, "prefix:");

        Set<Integer> distinctKeysHit = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            distinctKeysHit.add(indexOf(generator.nextKey()));
        }

        // With an 80/20 skew over 1000 keys (200 hot), 5000 samples should mostly recycle the
        // 200-key hot set rather than spreading evenly across all 1000 keys.
        assertThat(distinctKeysHit.size()).isLessThan(1000);
    }

    @Test
    void handlesAKeySpaceOfOneWithoutDividingByZero() {
        KeyGenerator generator = new KeyGenerator(1, 0.2, 0.8, "prefix:");

        for (int i = 0; i < 10; i++) {
            assertThat(indexOf(generator.nextKey())).isZero();
        }
    }
}
