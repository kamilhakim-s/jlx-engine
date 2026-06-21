package com.lowlatency.tuning;

import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import com.lowlatency.engine.disruptor.DisruptorMatchingService;
import com.lmax.disruptor.dsl.ProducerType;
import org.HdrHistogram.Histogram;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Reusable end-to-end latency harness for the Disruptor matching service. Generalises the Chunk 3
 * {@code DisruptorLatencyDemo} into something parameterised by {@link BenchmarkConfig} and wrapped in
 * GC/allocation observability, so the same workload can be driven under different wait strategies and
 * different garbage collectors and compared fairly.
 *
 * <p>The methodology is the Chunk 0 discipline, unchanged because it's non-negotiable:
 * <ul>
 *   <li><b>Warm up</b> untimed first (ingressNanos = 0 → the handler skips recording) so the JIT has
 *       compiled the hot path before we measure.</li>
 *   <li><b>Pace</b> the producer to a fixed rate and stamp each command with its <i>intended</i> send
 *       instant; latency is {@code now − intended}, measured from when the order was <i>due</i>. This is
 *       what defeats coordinated omission — a stall delays the victims and we see it.</li>
 *   <li>Report <b>percentiles</b>, plus the GC pauses and bytes allocated over the same window.</li>
 * </ul>
 */
public final class LatencyBenchmark {

    private static final long RESTING_PRICE = 100_000;
    private static final long DEEP = Long.MAX_VALUE / 4;
    private static final long MAX_TRACKABLE_NANOS = TimeUnit.SECONDS.toNanos(10);

    public BenchmarkResult run(BenchmarkConfig cfg) {
        Histogram latency = new Histogram(MAX_TRACKABLE_NANOS, 3);
        DisruptorMatchingService service = new DisruptorMatchingService(
                cfg.ringSize(), ProducerType.SINGLE, WaitStrategies.byName(cfg.waitStrategy()),
                cfg.ringSize(), latency);
        service.start();

        // Pin the producer thread before warmup, if requested. Acquiring the lock loads the native
        // library and scans the CPU topology (a one-time ~hundreds-of-ms cost) — doing it here means
        // that cost is absorbed by the untimed warmup, never charged to a measured sample. The lock is
        // held across both phases so the thread stays put.
        try (CpuAffinity affinity = cfg.pinCpu() ? CpuAffinity.pinToAnyCore() : null) {

            // Seed one deep resting sell so every measured buy matches against it (exercises the engine).
            service.publishNewOrder(1, Side.SELL, OrderType.LIMIT, RESTING_PRICE, DEEP, 0);

            // Warm up (untimed). Let the JIT compile the publish → match → record path before measuring.
            for (int i = 0; i < cfg.warmup(); i++) {
                service.publishNewOrder(2 + i, Side.BUY, OrderType.LIMIT, RESTING_PRICE, 1, 0);
            }
            awaitDrain(service, 1 + cfg.warmup());
            latency.reset(); // discard anything recorded during warmup

            // --- Begin the measured window: snapshot GC + allocation, then run paced & timed. ---
            GcStats gcBefore = GcStats.snapshot();
            long allocBefore = AllocationCounter.totalAllocatedBytes();

            long id = 2 + cfg.warmup();
            long interval = cfg.intervalNanos();
            long start = System.nanoTime();
            for (int i = 0; i < cfg.measured(); i++) {
                long intended = start + i * interval;
                while (System.nanoTime() < intended) {
                    Thread.onSpinWait();
                }
                service.publishNewOrder(id + i, Side.BUY, OrderType.LIMIT, RESTING_PRICE, 1, intended);
            }
            awaitDrain(service, 1 + cfg.warmup() + cfg.measured());
            long wallNanos = System.nanoTime() - start;

            long allocated = AllocationCounter.bytesSince(allocBefore);
            GcStats gcAfter = GcStats.snapshot();
            // --- End measured window. ---

            service.shutdown();

            return new BenchmarkResult(cfg.label(), latency,
                    gcAfter.collectionsSince(gcBefore), gcAfter.pauseMillisSince(gcBefore),
                    allocated, wallNanos, cfg.measured());
        }
    }

    private static void awaitDrain(DisruptorMatchingService service, long expectedProcessed) {
        while (service.processedCount() < expectedProcessed) {
            LockSupport.parkNanos(1_000);
        }
    }
}
