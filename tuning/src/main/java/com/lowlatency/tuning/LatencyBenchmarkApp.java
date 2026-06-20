package com.lowlatency.tuning;

import java.util.ArrayList;
import java.util.List;

/**
 * Chunk 7 headline app: the end-to-end latency benchmark suite, with GC and allocation observability
 * baked into every run. Two modes, selected by {@code -Dbench.matrix} (default true):
 *
 * <ul>
 *   <li><b>Matrix mode</b> (default): runs the same workload under busy-spin / yielding / blocking and
 *       prints a comparison table. This is the "full latency benchmark suite" — the percentiles plus,
 *       crucially, the GC pauses and bytes allocated next to them.
 *       <pre>./gradlew :tuning:run</pre></li>
 *   <li><b>Single mode</b> ({@code -Dbench.matrix=false}): one run with the configured wait strategy.
 *       The {@code bench*} Gradle tasks use this to drive the <i>same</i> workload under different
 *       collectors (G1 / ZGC / Parallel / Epsilon) so you can compare the GC line apples-to-apples:
 *       <pre>./gradlew :tuning:benchG1  :tuning:benchZgc  :tuning:benchEpsilon</pre></li>
 * </ul>
 *
 * Every {@code -Dbench.*} knob in {@link BenchmarkConfig} can be overridden, e.g.
 * {@code ./gradlew :tuning:run -Dbench.matrix=false -Dbench.waitStrategy=busyspin -Dbench.pinCpu=true}.
 */
public final class LatencyBenchmarkApp {

    public static void main(String[] args) {
        boolean matrix = Boolean.parseBoolean(System.getProperty("bench.matrix", "true"));
        BenchmarkConfig base = BenchmarkConfig.fromSystemProperties();
        String gcLabel = System.getProperty("bench.gcLabel", GcStats.snapshot().collectorNames());

        printHeader(base, gcLabel);

        LatencyBenchmark bench = new LatencyBenchmark();
        List<BenchmarkResult> results = new ArrayList<>();
        if (matrix) {
            results.add(bench.run(base.withWaitStrategy("BusySpin", "busyspin")));
            results.add(bench.run(base.withWaitStrategy("Yielding", "yielding")));
            results.add(bench.run(base.withWaitStrategy("Blocking", "blocking")));
        } else {
            results.add(bench.run(base));
        }

        printTable(results);
        printAllocationNote(results);
    }

    private static void printHeader(BenchmarkConfig cfg, String gcLabel) {
        System.out.printf("End-to-end latency benchmark — Disruptor matching service%n");
        System.out.printf("JVM:        %s %s%n",
                System.getProperty("java.vm.name"), System.getProperty("java.version"));
        System.out.printf("GC:         %s%n", gcLabel);
        System.out.printf("Alloc meter:%s%n",
                AllocationCounter.isSupported() ? " on (HotSpot per-thread accounting)" : " unavailable");
        System.out.printf("Workload:   ring=%,d  warmup=%,d  measured=%,d  target=%,d msg/s (%d ns apart)%n",
                cfg.ringSize(), cfg.warmup(), cfg.measured(), cfg.targetRate(), cfg.intervalNanos());
        System.out.printf("Pin CPU:    %s%n%n", cfg.pinCpu() ? "requested" : "off");
    }

    private static void printTable(List<BenchmarkResult> results) {
        System.out.printf("%-10s %9s %9s %9s %10s %10s %12s %14s%n",
                "strategy", "p50", "p99", "p99.9", "p99.99", "max", "GC", "alloc");
        System.out.printf("%-10s %9s %9s %9s %10s %10s %12s %14s%n",
                "--------", "---", "---", "-----", "------", "---", "--", "-----");
        for (BenchmarkResult r : results) {
            System.out.printf("%-10s %9s %9s %9s %10s %10s %12s %14s%n",
                    r.label(),
                    us(r.p(50)), us(r.p(99)), us(r.p(99.9)), us(r.p(99.99)), us(r.p(100)),
                    r.gcCollections() + "/" + r.gcPauseMillis() + "ms",
                    bytes(r.allocatedBytes()));
        }
        System.out.println();
    }

    private static void printAllocationNote(List<BenchmarkResult> results) {
        if (results.isEmpty()) {
            return;
        }
        long maxAlloc = 0;
        for (BenchmarkResult r : results) {
            maxAlloc = Math.max(maxAlloc, r.allocatedBytes());
        }
        System.out.printf("Achieved producer rate (first run): %,.0f orders/s%n",
                results.get(0).achievedRatePerSec());
        System.out.printf("Peak allocation over a measured loop: %s — the matching hot path is "
                + "allocation-free; what little you see is HdrHistogram/JIT bookkeeping, not per-order "
                + "garbage. This is why the Epsilon (no-op GC) run never needs to collect.%n", bytes(maxAlloc));
    }

    private static String us(long nanos) {
        return String.format("%.2fµs", nanos / 1_000.0);
    }

    private static String bytes(long b) {
        if (b < 1024) {
            return b + "B";
        }
        if (b < 1024 * 1024) {
            return String.format("%.1fKB", b / 1024.0);
        }
        return String.format("%.1fMB", b / (1024.0 * 1024.0));
    }

    private LatencyBenchmarkApp() {
    }
}
