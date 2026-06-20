package com.lowlatency.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests specific to the Chunk 2 design choices: flyweight reuse and pool recycling. */
class FastMatchingEngineTest {

    private Order order(FastMatchingEngine engine, long id, Side side, OrderType type, long price, long qty) {
        Order o = engine.orderPool().acquire();
        o.reset(id, side, type, price, qty);
        return o;
    }

    @Test
    void everyTradeIsTheSameReusedInstance() {
        FastOrderBook book = new FastOrderBook();
        AtomicReference<Trade> firstSeen = new AtomicReference<>();
        AtomicInteger count = new AtomicInteger();
        // A handler that checks identity: the engine must hand us the very same object each time.
        FastMatchingEngine engine = new FastMatchingEngine(book, t -> {
            count.incrementAndGet();
            firstSeen.compareAndSet(null, t);
            assertThat(t).isSameAs(firstSeen.get());
        }, 64);

        engine.submit(order(engine, 1, Side.SELL, OrderType.LIMIT, 100, 1));
        engine.submit(order(engine, 2, Side.SELL, OrderType.LIMIT, 100, 1));
        engine.submit(order(engine, 3, Side.BUY, OrderType.LIMIT, 100, 2)); // generates two trades

        assertThat(count.get()).isEqualTo(2);
    }

    @Test
    void fullyFilledOrdersAreReturnedToThePool() {
        FastOrderBook book = new FastOrderBook();
        FastMatchingEngine engine = new FastMatchingEngine(book, t -> { }, 8);
        int initial = engine.orderPool().available();

        // Maker rests (held by the book), so availability drops by one.
        engine.submit(order(engine, 1, Side.SELL, OrderType.LIMIT, 100, 5));
        assertThat(engine.orderPool().available()).isEqualTo(initial - 1);

        // Taker fully fills the maker: both order objects get recycled back to the pool, so after
        // acquiring 2 and releasing 2 the pool is back to where it started.
        engine.submit(order(engine, 2, Side.BUY, OrderType.LIMIT, 100, 5));
        assertThat(book.isEmpty()).isTrue();
        assertThat(engine.orderPool().available()).isEqualTo(initial);
    }
}
