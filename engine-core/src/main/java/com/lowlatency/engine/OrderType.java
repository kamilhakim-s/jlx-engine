package com.lowlatency.engine;

/**
 * Order types supported by the engine.
 *
 * <ul>
 *   <li>{@link #LIMIT} — executes at its price or better; any unfilled remainder rests in the book.</li>
 *   <li>{@link #MARKET} — executes against whatever liquidity exists, ignoring price; any unfilled
 *       remainder is discarded (a market order never rests).</li>
 * </ul>
 */
public enum OrderType {
    LIMIT,
    MARKET
}
