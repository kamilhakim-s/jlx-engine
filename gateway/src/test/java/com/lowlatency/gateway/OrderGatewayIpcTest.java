package com.lowlatency.gateway;

import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import com.lowlatency.engine.disruptor.DisruptorMatchingService;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the full wire path on an embedded Aeron media driver over the IPC channel — no
 * network, no external driver. Proves that orders published over Aeron are decoded by the gateway and
 * matched by the engine, exactly as if they'd been fed in-process. A backoff idle strategy keeps the
 * gateway poll thread from pegging a core during the test.
 */
class OrderGatewayIpcTest {

    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1001;

    private MediaDriver mediaDriver;
    private Aeron aeron;
    private DisruptorMatchingService engine;
    private OrderGateway gateway;

    @BeforeEach
    void setUp() {
        mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));
        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));
        engine = new DisruptorMatchingService(1 << 14, 1 << 14);
        engine.start();
        Subscription subscription = aeron.addSubscription(CHANNEL, STREAM_ID);
        gateway = new OrderGateway(subscription, engine,
                new BackoffIdleStrategy(100, 10, 1_000, 100_000));
        gateway.start();
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.stop();
        }
        if (engine != null) {
            engine.shutdown();
        }
        if (aeron != null) {
            aeron.close();
        }
        if (mediaDriver != null) {
            mediaDriver.close();
        }
    }

    @Test
    void ordersSentOverAeronAreMatchedByTheEngine() {
        Publication publication = aeron.addPublication(CHANNEL, STREAM_ID);
        try (OrderEntryClient client = new OrderEntryClient(publication)) {
            client.awaitConnected();

            // One deep resting sell, then 1,000 buys of 1 unit each — every buy should match the sell.
            client.sendNewOrder(1, Side.SELL, OrderType.LIMIT, 100_000, 10_000, 0);
            for (int i = 0; i < 1_000; i++) {
                client.sendNewOrder(2 + i, Side.BUY, OrderType.LIMIT, 100_000, 1, 0);
            }

            awaitProcessed(1 + 1_000);

            assertThat(engine.processedCount()).isEqualTo(1 + 1_000);
            assertThat(gateway.decodedCount()).isEqualTo(1 + 1_000);
            assertThat(engine.matchedQuantity()).isEqualTo(1_000); // 1,000 buys × 1 unit
            assertThat(engine.tradeCount()).isEqualTo(1_000);
        }
    }

    @Test
    void cancelSentOverAeronRemovesRestingLiquidity() {
        Publication publication = aeron.addPublication(CHANNEL, STREAM_ID);
        try (OrderEntryClient client = new OrderEntryClient(publication)) {
            client.awaitConnected();

            // Rest a sell, cancel it, then a buy at the same price finds nothing to match.
            client.sendNewOrder(1, Side.SELL, OrderType.LIMIT, 100_000, 5, 0);
            client.sendCancel(1, 0);
            client.sendNewOrder(2, Side.BUY, OrderType.LIMIT, 100_000, 5, 0);

            awaitProcessed(3);

            assertThat(gateway.decodedCount()).isEqualTo(3);
            assertThat(engine.matchedQuantity()).isZero(); // the resting sell was cancelled before the buy
        }
    }

    private void awaitProcessed(long expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (engine.processedCount() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out: processed "
                        + engine.processedCount() + " of " + expected);
            }
            LockSupport.parkNanos(50_000);
        }
    }
}
