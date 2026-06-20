package com.lowlatency.engine;

/**
 * Callback invoked synchronously for every trade an engine produces, in match order.
 *
 * <p>Using a push callback rather than returning a {@code List<Trade>} keeps the hot path
 * allocation-free (no collection to build) and is the shape the Disruptor wiring in Chunk 3 wants.
 * Implementations must be fast and non-blocking — they run inside {@code submit}.
 */
@FunctionalInterface
public interface TradeHandler {
    void onTrade(Trade trade);
}
