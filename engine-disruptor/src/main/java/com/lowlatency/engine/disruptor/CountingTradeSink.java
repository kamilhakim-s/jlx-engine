package com.lowlatency.engine.disruptor;

import com.lowlatency.engine.Trade;
import com.lowlatency.engine.TradeHandler;

/**
 * Counts trades and sums matched quantity. It is written only by the single consumer thread, so the
 * fields can be plain (no atomics); they are {@code volatile} purely so other threads (the demo/test)
 * can read a consistent value after the pipeline drains.
 */
final class CountingTradeSink implements TradeHandler {

    private volatile long tradeCount;
    private volatile long matchedQuantity;

    @Override
    public void onTrade(Trade trade) {
        tradeCount++;
        matchedQuantity += trade.quantity();
    }

    long tradeCount() {
        return tradeCount;
    }

    long matchedQuantity() {
        return matchedQuantity;
    }
}
