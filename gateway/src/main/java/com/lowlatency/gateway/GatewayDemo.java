package com.lowlatency.gateway;

import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import com.lowlatency.engine.disruptor.DisruptorMatchingService;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.HdrHistogram.Histogram;
import org.agrona.concurrent.BusySpinIdleStrategy;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * End-to-end latency of the full wire path: an {@link OrderEntryClient} encodes orders and publishes them
 * over <b>Aeron</b>; the {@link OrderGateway} decodes them and feeds the Disruptor matching engine. We
 * measure client → Aeron → gateway → ring buffer → match, in nanoseconds, and print percentiles.
 *
 * <pre>./gradlew :gateway:run</pre>
 *
 * <p>For self-containment the demo launches an <b>embedded media driver</b> and uses the shared-memory
 * <b>IPC</b> channel ({@code aeron:ipc}) — no external driver, no network config. Point it at UDP with
 * {@code -Dgateway.channel="aeron:udp?endpoint=localhost:40123"} to exercise the network stack instead.
 *
 * <p>Methodology is the project standard (Chunk 0): warm up untimed, pace the producer to a fixed rate,
 * stamp each order with its <i>intended</i> send instant and measure from there (coordinated-omission
 * safe), report percentiles. Client and gateway share this JVM, so {@code System.nanoTime()} is directly
 * comparable; across hosts you'd need synchronised clocks (noted in the doc).
 */
public final class GatewayDemo {

    private static final int STREAM_ID = 10;
    private static final int RING_SIZE = 1 << 16;
    private static final int WARMUP = 200_000;
    private static final int MEASURED = 1_000_000;
    private static final long TARGET_RATE = 500_000;
    private static final long INTERVAL_NS = TimeUnit.SECONDS.toNanos(1) / TARGET_RATE;

    private static final long RESTING_PRICE = 100_000;
    private static final long DEEP = Long.MAX_VALUE / 4;

    public static void main(String[] args) {
        String channel = System.getProperty("gateway.channel", "aeron:ipc");
        System.out.printf("Order-entry gateway — end-to-end latency over Aeron%n");
        System.out.printf("channel=%s  stream=%d  warmup=%,d  measured=%,d  target=%,d msg/s (%d ns apart)%n%n",
                channel, STREAM_ID, WARMUP, MEASURED, TARGET_RATE, INTERVAL_NS);

        // Embedded driver: SHARED threading mode runs the driver's duties on one thread — fine for a demo;
        // a latency deployment uses DEDICATED (conductor/sender/receiver on their own cores).
        MediaDriver mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));

        Histogram latency = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);

        try (Aeron aeron = Aeron.connect(new Aeron.Context()
                .aeronDirectoryName(mediaDriver.aeronDirectoryName()))) {

            // The gateway thread is the ONLY publisher into the ring buffer → single-writer preserved.
            DisruptorMatchingService engine = new DisruptorMatchingService(RING_SIZE, RING_SIZE, latency);
            engine.start();

            Subscription subscription = aeron.addSubscription(channel, STREAM_ID);
            Publication publication = aeron.addPublication(channel, STREAM_ID);

            OrderGateway gateway = new OrderGateway(subscription, engine, new BusySpinIdleStrategy());
            gateway.start();

            try (OrderEntryClient client = new OrderEntryClient(publication)) {
                client.awaitConnected();

                // Everything arrives over the wire, including the resting order — so the gateway thread
                // stays the single ring-buffer writer. Aeron preserves order on the stream, so the deep
                // sell is matched before the buys that follow.
                client.sendNewOrder(1, Side.SELL, OrderType.LIMIT, RESTING_PRICE, DEEP, 0);

                for (long i = 0; i < WARMUP; i++) {
                    client.sendNewOrder(2 + i, Side.BUY, OrderType.LIMIT, RESTING_PRICE, 1, 0);
                }
                awaitProcessed(engine, 1 + WARMUP);
                latency.reset();   // discard warmup samples

                long id = 2 + WARMUP;
                long start = System.nanoTime();
                for (int i = 0; i < MEASURED; i++) {
                    long intended = start + i * INTERVAL_NS;
                    while (System.nanoTime() < intended) {
                        Thread.onSpinWait();
                    }
                    client.sendNewOrder(id + i, Side.BUY, OrderType.LIMIT, RESTING_PRICE, 1, intended);
                }
                awaitProcessed(engine, 1 + WARMUP + MEASURED);

                print(latency, client.backpressureCount(), engine.matchedQuantity());
            }

            gateway.stop();
            engine.shutdown();
        } finally {
            mediaDriver.close();
        }
    }

    private static void awaitProcessed(DisruptorMatchingService engine, long expected) {
        while (engine.processedCount() < expected) {
            LockSupport.parkNanos(1_000);
        }
    }

    private static void print(Histogram h, long backpressure, long matchedQty) {
        System.out.printf("End-to-end latency (client → Aeron → gateway → engine match):%n");
        System.out.printf("  p50=%s  p99=%s  p99.9=%s  p99.99=%s  max=%s%n",
                us(h.getValueAtPercentile(50)), us(h.getValueAtPercentile(99)),
                us(h.getValueAtPercentile(99.9)), us(h.getValueAtPercentile(99.99)), us(h.getMaxValue()));
        System.out.printf("  matched qty=%,d   publisher backpressure retries=%,d%n", matchedQty, backpressure);
    }

    private static String us(long nanos) {
        return String.format("%.2f µs", nanos / 1_000.0);
    }

    private GatewayDemo() {
    }
}
