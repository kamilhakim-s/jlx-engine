package com.lowlatency.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proof that Chunk 2's optimisation changed performance but not behaviour: drive a long,
 * randomised stream of orders and cancels through both the Chunk 1 reference engine and the Chunk 2
 * fast engine, and assert they produce the <b>identical</b> trade sequence and the identical final
 * book. {@link MatchingEngineTest} pins the reference engine's semantics; this test pins the fast
 * engine to the reference.
 */
class EngineEquivalenceTest {

    @Test
    void randomisedFlowProducesIdenticalTradesAndBook() {
        // Reference (Chunk 1) engine.
        OrderBook refBook = new OrderBook();
        CollectingTradeHandler refTrades = new CollectingTradeHandler();
        MatchingEngine reference = new MatchingEngine(refBook, refTrades);

        // Fast (Chunk 2) engine.
        FastOrderBook fastBook = new FastOrderBook();
        CollectingTradeHandler fastTrades = new CollectingTradeHandler();
        FastMatchingEngine fast = new FastMatchingEngine(fastBook, fastTrades, 4096);

        Random rnd = new Random(0xC0FFEE); // fixed seed ⇒ reproducible
        List<Long> maybeResting = new ArrayList<>();
        long nextId = 1;

        for (int i = 0; i < 50_000; i++) {
            boolean doCancel = !maybeResting.isEmpty() && rnd.nextInt(100) < 15;
            if (doCancel) {
                long id = maybeResting.remove(rnd.nextInt(maybeResting.size()));
                // A no-op in both engines if the order already filled / never rested — and it must be
                // a no-op in the SAME way, which is part of what we're checking.
                assertThat(fast.cancel(id)).isEqualTo(reference.cancel(id));
            } else {
                long id = nextId++;
                Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                OrderType type = rnd.nextInt(10) == 0 ? OrderType.MARKET : OrderType.LIMIT;
                long price = 95 + rnd.nextInt(11); // 95..105 → lots of crossing
                long qty = 1 + rnd.nextInt(10);

                reference.submit(new Order(id, side, type, price, qty));

                Order pooled = fast.orderPool().acquire();
                pooled.reset(id, side, type, price, qty);
                fast.submit(pooled);

                if (type == OrderType.LIMIT) {
                    maybeResting.add(id);
                }
            }
        }

        // Same trades, in the same order, with the same sequence numbers.
        assertThat(fastTrades.trades()).isEqualTo(refTrades.trades());
        assertThat(fastTrades.trades()).isNotEmpty(); // sanity: the workload actually traded

        // Same resulting book at every price on both sides.
        assertThat(fastBook.bestBid()).isEqualTo(refBook.bestBid());
        assertThat(fastBook.bestAsk()).isEqualTo(refBook.bestAsk());
        for (long price = 95; price <= 105; price++) {
            assertThat(fastBook.quantityAt(Side.BUY, price))
                    .as("bid qty @%d", price).isEqualTo(refBook.quantityAt(Side.BUY, price));
            assertThat(fastBook.quantityAt(Side.SELL, price))
                    .as("ask qty @%d", price).isEqualTo(refBook.quantityAt(Side.SELL, price));
        }
    }
}
