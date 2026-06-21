package com.lowlatency.tuning;

/**
 * Parameters for one end-to-end latency benchmark run. Immutable; built from defaults or overridden via
 * {@code -Dbench.*} system properties so the GC-experiment Gradle tasks can vary one knob at a time.
 *
 * @param label          human label for the report line
 * @param waitStrategy   Disruptor wait strategy name (busyspin|yielding|blocking|sleeping)
 * @param ringSize       ring-buffer slots (power of two)
 * @param warmup         untimed orders pushed first so the JIT compiles the hot path before we measure
 * @param measured       timed orders, paced to {@code targetRate}
 * @param targetRate     producer pacing in commands/sec (latency is timed from the intended send instant)
 * @param pinCpu         attempt to pin the producer thread to a core (real only on Linux)
 */
public record BenchmarkConfig(
        String label,
        String waitStrategy,
        int ringSize,
        int warmup,
        int measured,
        long targetRate,
        boolean pinCpu) {

    public long intervalNanos() {
        return 1_000_000_000L / targetRate;
    }

    /** Same config with a different wait strategy + label (used to build the comparison matrix). */
    public BenchmarkConfig withWaitStrategy(String label, String waitStrategy) {
        return new BenchmarkConfig(label, waitStrategy, ringSize, warmup, measured, targetRate, pinCpu);
    }

    /** Defaults, mirroring the Chunk 3 demo (1M measured orders at 500k/s). */
    public static BenchmarkConfig defaults() {
        return new BenchmarkConfig("busyspin", "busyspin", 1 << 16, 200_000, 1_000_000, 500_000, false);
    }

    /** Defaults with any {@code -Dbench.*} system properties applied on top. */
    public static BenchmarkConfig fromSystemProperties() {
        BenchmarkConfig d = defaults();
        String waitStrategy = System.getProperty("bench.waitStrategy", d.waitStrategy());
        return new BenchmarkConfig(
                System.getProperty("bench.label", waitStrategy),
                waitStrategy,
                intProp("bench.ringSize", d.ringSize()),
                intProp("bench.warmup", d.warmup()),
                intProp("bench.measured", d.measured()),
                longProp("bench.targetRate", d.targetRate()),
                Boolean.parseBoolean(System.getProperty("bench.pinCpu", String.valueOf(d.pinCpu()))));
    }

    private static int intProp(String key, int fallback) {
        String v = System.getProperty(key);
        return v == null ? fallback : Integer.parseInt(v.trim());
    }

    private static long longProp(String key, long fallback) {
        String v = System.getProperty(key);
        return v == null ? fallback : Long.parseLong(v.trim());
    }
}
