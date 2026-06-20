package com.lowlatency.marketdata;

import com.lowlatency.engine.Side;

/**
 * A normalised Binance <b>aggregate trade</b>: one or more market orders that executed against the
 * same resting order at the same price, collapsed into a single print. This is the unit of both the
 * historical bulk files and the live {@code @aggTrade} stream, so the rest of the pipeline only ever
 * sees this internal shape.
 *
 * <p>Price and quantity are already integer-scaled (see {@link SymbolScale}); time is epoch millis.
 * {@code buyerIsMaker} is Binance's flag for which side rested: if the buyer was the maker, the
 * <i>aggressor</i> (taker) was the seller, and vice-versa — exposed via {@link #takerSide()}.
 *
 * @param aggTradeId   Binance aggregate-trade id (monotonic per symbol)
 * @param priceTicks   execution price in integer ticks
 * @param quantityUnits executed size in integer units
 * @param timestampMillis exchange transact time (epoch millis)
 * @param buyerIsMaker true if the buy side was the resting (maker) order
 */
public record AggTrade(
        long aggTradeId,
        long priceTicks,
        long quantityUnits,
        long timestampMillis,
        boolean buyerIsMaker) {

    /** The side that crossed the spread (took liquidity). */
    public Side takerSide() {
        return buyerIsMaker ? Side.SELL : Side.BUY;
    }
}
