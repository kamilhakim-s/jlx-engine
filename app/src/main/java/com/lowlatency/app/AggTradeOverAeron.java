package com.lowlatency.app;

import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import com.lowlatency.gateway.OrderEntryClient;
import com.lowlatency.marketdata.AggTrade;

import java.util.function.Consumer;

/**
 * Sends each {@link AggTrade} into the engine <b>over Aeron</b>, reconstructing the public print as a
 * crossing maker/taker pair — the same reconstruction Chunk 4's {@code MarketDataReplayer} does, but
 * targeting the {@link OrderEntryClient} (the wire) instead of calling the engine directly.
 *
 * <p>Putting the gateway in the middle is the whole point of the capstone: market data no longer touches
 * the engine in-process; it crosses the transport boundary just like a remote order source would. The
 * maker rests untimed; the taker carries an ingress timestamp (when measuring) so the engine records
 * the full client → Aeron → gateway → match latency.
 */
public final class AggTradeOverAeron implements Consumer<AggTrade> {

    private final OrderEntryClient client;
    private long nextOrderId = 1;
    private long sentTrades;
    private volatile boolean measureLatency;

    public AggTradeOverAeron(OrderEntryClient client) {
        this.client = client;
    }

    /** Toggle latency stamping — off during warmup, on for the measured run. */
    public void setMeasureLatency(boolean measureLatency) {
        this.measureLatency = measureLatency;
    }

    @Override
    public void accept(AggTrade trade) {
        Side takerSide = trade.takerSide();
        Side makerSide = takerSide.opposite();
        long price = trade.priceTicks();
        long qty = trade.quantityUnits();

        // Maker rests first (untimed); taker crosses it and produces the trade (optionally timed).
        client.sendNewOrder(nextOrderId++, makerSide, OrderType.LIMIT, price, qty, 0);
        long ingress = measureLatency ? System.nanoTime() : 0;
        client.sendNewOrder(nextOrderId++, takerSide, OrderType.LIMIT, price, qty, ingress);

        sentTrades++;
    }

    /** Number of source trades sent (each became two orders on the wire). */
    public long sentTrades() {
        return sentTrades;
    }
}
