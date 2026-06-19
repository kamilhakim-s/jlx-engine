package com.lowlatency.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A first JMH microbenchmark. It contrasts two ways of reading a value by an int key:
 *
 * <ul>
 *   <li>{@code int[]} indexed access — one bounds-checked array load, no allocation.</li>
 *   <li>{@code HashMap<Integer,Integer>} — hashing, a chase to a bucket, and (critically)
 *       autoboxing: every {@code get(key)} boxes the int key into an {@link Integer} object.</li>
 * </ul>
 *
 * <p>The point isn't that one "wins" — it's to learn the JMH discipline (warmup, forks, blackholes
 * to defeat dead-code elimination) and to <i>see</i> the cost of boxing and pointer-chasing that we
 * will eliminate from the matching engine's hot path in Chunk 2.
 *
 * <p>Run: {@code ./gradlew :benchmarks:jmh}
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class LookupBenchmark {

    private static final int SIZE = 1_024;

    private int[] array;
    private Map<Integer, Integer> map;
    private int key;

    @Setup(Level.Trial)
    public void setup() {
        array = new int[SIZE];
        map = new HashMap<>(SIZE * 2);
        for (int i = 0; i < SIZE; i++) {
            array[i] = i * 7;
            map.put(i, i * 7);
        }
    }

    /** Advance the key each invocation so the JIT can't fold the result to a constant. */
    private int nextKey() {
        key = (key + 1) & (SIZE - 1);
        return key;
    }

    @Benchmark
    public void arrayGet(Blackhole bh) {
        bh.consume(array[nextKey()]);
    }

    @Benchmark
    public void hashMapGet(Blackhole bh) {
        bh.consume(map.get(nextKey())); // autoboxes the key -> Integer on every call
    }
}
