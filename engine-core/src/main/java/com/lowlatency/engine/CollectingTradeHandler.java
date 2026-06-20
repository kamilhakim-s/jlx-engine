package com.lowlatency.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link TradeHandler} that copies every trade into an immutable {@link TradeRecord} and stores it.
 * Handy for tests and demos. The copy matters: it makes this handler safe with engines that reuse a
 * single mutable {@link Trade} instance (Chunk 2), and it lets us compare the two engines' output for
 * exact equivalence.
 */
public final class CollectingTradeHandler implements TradeHandler {

    private final List<Trade> trades = new ArrayList<>();

    @Override
    public void onTrade(Trade t) {
        trades.add(new TradeRecord(
                t.takerOrderId(), t.makerOrderId(), t.takerSide(), t.price(), t.quantity(), t.sequence()));
    }

    public List<Trade> trades() {
        return trades;
    }

    public void clear() {
        trades.clear();
    }
}
