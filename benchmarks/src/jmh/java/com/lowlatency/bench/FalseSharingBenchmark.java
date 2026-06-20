package com.lowlatency.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Demonstrates <b>false sharing</b> — the hardware effect that motivates the Disruptor's padding and
 * the {@code @Contended} annotation (Chunk 3).
 *
 * <p>CPUs move memory in <b>cache lines</b> (typically 64 bytes), not individual variables. If two
 * threads write two <i>different</i> fields that happen to sit on the <i>same</i> cache line, every
 * write invalidates the other core's copy of the line, forcing it to be re-fetched. The fields are
 * logically independent, yet the cores fight over the line — "false" sharing.
 *
 * <p>Two threads run concurrently (one per {@code @Benchmark} method in a {@code @Group}). In the
 * {@code adjacent} group the two counters share a cache line; in the {@code padded} group 7 longs of
 * padding push them onto separate lines. Compare the combined throughput:
 *
 * <pre>./gradlew :benchmarks:jmh -Pjmh.includes=FalseSharingBenchmark</pre>
 *
 * The padded group should show markedly higher throughput — same logic, just a better memory layout.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Group)
public class FalseSharingBenchmark {

    /** Two counters on (very likely) the same cache line. */
    static final class Adjacent {
        volatile long a;
        volatile long b;
    }

    /** Two counters separated by 7 longs (56 bytes) of padding ⇒ different cache lines. */
    static final class Padded {
        volatile long a;
        @SuppressWarnings("unused")
        long p1, p2, p3, p4, p5, p6, p7;
        volatile long b;
    }

    private final Adjacent adjacent = new Adjacent();
    private final Padded padded = new Padded();

    @Benchmark
    @Group("adjacent")
    public void adjacentWriteA() {
        adjacent.a++;
    }

    @Benchmark
    @Group("adjacent")
    public void adjacentWriteB() {
        adjacent.b++;
    }

    @Benchmark
    @Group("padded")
    public void paddedWriteA() {
        padded.a++;
    }

    @Benchmark
    @Group("padded")
    public void paddedWriteB() {
        padded.b++;
    }
}
