package com.lowlatency.streaming;

/**
 * The trade record that crosses the Kafka boundary out of the engine. It is the streaming-tier
 * counterpart to the engine's {@code Trade}: a flat, serialisable snapshot (the engine's reused
 * flyweight can't leave the hot path). Price/quantity stay integer-scaled; {@code timestampMillis} is
 * the event time used for Flink windowing.
 *
 * @param symbol         instrument, e.g. "BTCUSDT" (the Kafka partition key)
 * @param sequence       engine-assigned monotonic trade sequence
 * @param priceTicks     execution price in integer ticks
 * @param quantityUnits  matched size in integer units
 * @param takerBuy       true if the aggressor was a buy (taker side = BUY)
 * @param timestampMillis event time (epoch millis)
 */
public record TradeEvent(
        String symbol,
        long sequence,
        long priceTicks,
        long quantityUnits,
        boolean takerBuy,
        long timestampMillis) {
}
