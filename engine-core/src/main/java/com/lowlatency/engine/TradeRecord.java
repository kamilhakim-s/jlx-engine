package com.lowlatency.engine;

/**
 * Immutable {@link Trade} implementation used by the correctness-first Chunk 1 engine. Allocating a
 * record per trade is fine here — Chunk 1 optimises for clarity, not garbage. Chunk 2 replaces this
 * with a reused mutable instance on the hot path.
 */
public record TradeRecord(
        long takerOrderId,
        long makerOrderId,
        Side takerSide,
        long price,
        long quantity,
        long sequence) implements Trade {
}
