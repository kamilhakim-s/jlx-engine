package com.lowlatency.journal;

import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import com.lowlatency.engine.disruptor.DisruptorMatchingService;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates a deterministic stream of orders and cancels and publishes it into a
 * {@link DisruptorMatchingService}. A fixed seed makes the whole session reproducible, which is what
 * lets the journal round-trip assert byte-for-byte equality.
 */
final class SyntheticOrderFlow {

    static void feed(DisruptorMatchingService service, int commands, long seed) {
        Random rnd = new Random(seed);
        List<Long> maybeResting = new ArrayList<>();
        long nextId = 1;

        for (int i = 0; i < commands; i++) {
            if (!maybeResting.isEmpty() && rnd.nextInt(100) < 15) {
                long id = maybeResting.remove(rnd.nextInt(maybeResting.size()));
                service.publishCancel(id, 0);
            } else {
                long id = nextId++;
                Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                OrderType type = rnd.nextInt(10) == 0 ? OrderType.MARKET : OrderType.LIMIT;
                long price = 95 + rnd.nextInt(11); // 95..105 → lots of crossing
                long qty = 1 + rnd.nextInt(10);
                service.publishNewOrder(id, side, type, price, qty, 0);
                if (type == OrderType.LIMIT) {
                    maybeResting.add(id);
                }
            }
        }
    }

    private SyntheticOrderFlow() {
    }
}
