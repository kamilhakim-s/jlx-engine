package com.lowlatency.engine.disruptor;

import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.ProducerType;
import org.HdrHistogram.Histogram;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Measures end-to-end latency (publish → matched) of the Disruptor matching service under three wait
 * strategies, and prints HdrHistogram percentiles for each. Run:
 *
 * <pre>./gradlew :engine-disruptor:run</pre>
 *
 * <p>Methodology, applying the Chunk 0 lessons:
 * <ul>
 *   <li><b>Warm up</b> first (untimed) so the JIT has compiled the hot path before we measure.</li>
 *   <li><b>Pace</b> the producer at a fixed target rate and stamp each command with its <i>intended</i>
 *       send time, so latency is measured from when the command was <i>due</i> — this avoids
 *       coordinated omission.</li>
 *   <li>Report <b>percentiles</b> (p50/p99/p99.9/p99.99/max), never the mean.</li>
 * </ul>
 */
public final class DisruptorLatencyDemo {

    private static final int RING_SIZE = 1 << 16;     // 65,536 slots (power of two)
    private static final int EXPECTED_ORDERS = 1 << 16;
    private static final int WARMUP = 200_000;
    private static final int MEASURED = 1_000_000;
    private static final long TARGET_RATE = 500_000;  // commands/sec
    private static final long INTERVAL_NS = TimeUnit.SECONDS.toNanos(1) / TARGET_RATE;

    private static final long RESTING_PRICE = 100_000;
    private static final long DEEP = Long.MAX_VALUE / 4;

    public static void main(String[] args) {
        System.out.printf("Disruptor matching engine — end-to-end latency%n");
        System.out.printf("ring=%,d  warmup=%,d  measured=%,d  target=%,d msg/s (%d ns apart)%n%n",
                RING_SIZE, WARMUP, MEASURED, TARGET_RATE, INTERVAL_NS);

        run("BusySpin (lowest latency, burns a core)", new BusySpinWaitStrategy());
        run("Yielding (low latency, friendlier)", new YieldingWaitStrategy());
        run("Blocking (lock/condition, lowest CPU)", new BlockingWaitStrategy());
    }

    private static void run(String label, WaitStrategy waitStrategy) {
        Histogram latency = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
        DisruptorMatchingService service = new DisruptorMatchingService(
                RING_SIZE, ProducerType.SINGLE, waitStrategy, EXPECTED_ORDERS, latency);
        service.start();

        // Seed one deep resting sell so every measured buy matches against it (exercises the engine).
        service.publishNewOrder(1, Side.SELL, OrderType.LIMIT, RESTING_PRICE, DEEP, 0);

        // Warm up (untimed: ingressNanos = 0 so the handler skips recording).
        for (int i = 0; i < WARMUP; i++) {
            service.publishNewOrder(2 + i, Side.BUY, OrderType.LIMIT, RESTING_PRICE, 1, 0);
        }
        awaitDrain(service, 1 + WARMUP);
        latency.reset(); // discard anything recorded during warmup

        // Measured run, paced to TARGET_RATE, timed from the intended send instant.
        long id = 2 + WARMUP;
        long start = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) {
            long intended = start + i * INTERVAL_NS;
            while (System.nanoTime() < intended) {
                Thread.onSpinWait();
            }
            service.publishNewOrder(id + i, Side.BUY, OrderType.LIMIT, RESTING_PRICE, 1, intended);
        }
        awaitDrain(service, 1 + WARMUP + MEASURED);
        service.shutdown();

        print(label, latency);
    }

    private static void awaitDrain(DisruptorMatchingService service, long expectedProcessed) {
        while (service.processedCount() < expectedProcessed) {
            LockSupport.parkNanos(1_000);
        }
    }

    private static void print(String label, Histogram h) {
        System.out.println(label);
        System.out.printf("  p50=%s  p99=%s  p99.9=%s  p99.99=%s  max=%s%n%n",
                us(h.getValueAtPercentile(50)),
                us(h.getValueAtPercentile(99)),
                us(h.getValueAtPercentile(99.9)),
                us(h.getValueAtPercentile(99.99)),
                us(h.getMaxValue()));
    }

    private static String us(long nanos) {
        return String.format("%.2f µs", nanos / 1_000.0);
    }

    private DisruptorLatencyDemo() {
    }
}
