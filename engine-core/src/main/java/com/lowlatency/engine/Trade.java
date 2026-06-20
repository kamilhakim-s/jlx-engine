package com.lowlatency.engine;

/**
 * A read-only view of an executed trade (a match between an aggressing order and a resting order).
 *
 * <p>It is an interface, not a class, on purpose: the Chunk 1 engine hands out an immutable
 * {@link TradeRecord}, while the Chunk 2 zero-allocation engine hands out a single reused mutable
 * instance. Consumers code against this interface and must <b>not</b> retain the reference past the
 * {@link TradeHandler#onTrade} call (the Chunk 2 instance is overwritten on the next trade) — copy
 * the fields out if you need to keep them.
 */
public interface Trade {

    /** Id of the aggressing (incoming) order that crossed the spread. */
    long takerOrderId();

    /** Id of the resting order that was matched. */
    long makerOrderId();

    /** Side of the aggressor; the side that "took" liquidity. */
    Side takerSide();

    /** Execution price — always the resting (maker) order's price. */
    long price();

    /** Matched quantity. */
    long quantity();

    /** Monotonic engine-assigned sequence number, useful for ordering and replay. */
    long sequence();
}
