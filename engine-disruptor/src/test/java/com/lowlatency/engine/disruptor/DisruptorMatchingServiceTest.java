package com.lowlatency.engine.disruptor;

import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.ProducerType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the engine behaves correctly when driven through the asynchronous ring-buffer path, and
 * that many concurrent producers are safely serialised onto the single consumer thread.
 */
class DisruptorMatchingServiceTest {

    private static DisruptorMatchingService newService(ProducerType producerType) {
        return new DisruptorMatchingService(
                1 << 14, producerType, new YieldingWaitStrategy(), 1 << 14, null);
    }

    private static void awaitProcessed(DisruptorMatchingService service, long expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (service.processedCount() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out: processed "
                        + service.processedCount() + " of " + expected);
            }
            LockSupport.parkNanos(10_000);
        }
    }

    @Test
    void matchesOrdersDrivenThroughTheRingBuffer() {
        DisruptorMatchingService service = newService(ProducerType.SINGLE);
        service.start();

        service.publishNewOrder(1, Side.SELL, OrderType.LIMIT, 100, 5, 0); // rests
        service.publishNewOrder(2, Side.SELL, OrderType.LIMIT, 101, 5, 0); // rests
        service.publishNewOrder(3, Side.BUY, OrderType.LIMIT, 101, 8, 0);  // takes 5@100 + 3@101

        service.shutdown(); // drains all outstanding events before returning

        assertThat(service.processedCount()).isEqualTo(3);
        assertThat(service.tradeCount()).isEqualTo(2);
        assertThat(service.matchedQuantity()).isEqualTo(8);
    }

    @Test
    void manyProducersAreSerialisedOntoTheSingleWriter() throws InterruptedException {
        DisruptorMatchingService service = newService(ProducerType.MULTI);
        service.start();

        // One deep resting sell; many threads fire single-unit buys that each fully match it.
        service.publishNewOrder(1, Side.SELL, OrderType.LIMIT, 100, Long.MAX_VALUE / 4, 0);

        int producers = 4;
        int perProducer = 25_000;
        Thread[] threads = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            final long base = 100L + (long) p * perProducer;
            threads[p] = new Thread(() -> {
                for (int i = 0; i < perProducer; i++) {
                    service.publishNewOrder(base + i, Side.BUY, OrderType.LIMIT, 100, 1, 0);
                }
            });
        }
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        long expectedCommands = 1L + (long) producers * perProducer;
        awaitProcessed(service, expectedCommands);
        service.shutdown();

        // No lost updates, no corruption: every buy matched exactly one unit despite 4 racing producers.
        assertThat(service.processedCount()).isEqualTo(expectedCommands);
        assertThat(service.tradeCount()).isEqualTo((long) producers * perProducer);
        assertThat(service.matchedQuantity()).isEqualTo((long) producers * perProducer);
    }
}
