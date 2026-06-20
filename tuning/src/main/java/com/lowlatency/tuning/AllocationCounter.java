package com.lowlatency.tuning;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Measures heap bytes allocated by the JVM's threads over a benchmark run, via HotSpot's
 * {@link com.sun.management.ThreadMXBean#getThreadAllocatedBytes(long[])}. Take a baseline before the
 * measured loop and read {@link #bytesSince(long)} after.
 *
 * <p>This is the number the whole project has been chasing since Chunk 2: the matching <i>hot path</i>
 * should allocate ~zero bytes per order. Allocation is the root cause of GC pauses, so "bytes allocated
 * during the measured loop" is the leading indicator of tail latency — measure it directly rather than
 * waiting for a pause to show up in the histogram. It also explains <i>why</i> the Epsilon (no-op GC)
 * experiment survives: if this stays small, there is nothing for a collector to reclaim.
 *
 * <p>The measurement is approximate by design — it sums per-thread allocation across all live threads,
 * so threads that start or die mid-run are only partially counted. For a fixed producer + consumer that
 * live for the whole run (our case) it's accurate enough to compare orders of magnitude.
 */
public final class AllocationCounter {

    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean SUN_THREAD_MX =
            (THREAD_MX instanceof com.sun.management.ThreadMXBean sun) ? sun : null;

    /** True on HotSpot, where per-thread allocation accounting is available. */
    public static boolean isSupported() {
        return SUN_THREAD_MX != null && SUN_THREAD_MX.isThreadAllocatedMemorySupported();
    }

    /** Total bytes allocated by all live threads so far. Use as a baseline, then diff with it. */
    public static long totalAllocatedBytes() {
        if (!isSupported()) {
            return 0;
        }
        long[] ids = THREAD_MX.getAllThreadIds();
        long[] bytes = SUN_THREAD_MX.getThreadAllocatedBytes(ids);
        long total = 0;
        for (long b : bytes) {
            if (b > 0) {
                total += b;
            }
        }
        return total;
    }

    /** Bytes allocated since the {@code baseline} returned by an earlier {@link #totalAllocatedBytes()}. */
    public static long bytesSince(long baseline) {
        return totalAllocatedBytes() - baseline;
    }

    private AllocationCounter() {
    }
}
