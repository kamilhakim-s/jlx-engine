package com.lowlatency.marketdata;

import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import com.lowlatency.engine.disruptor.DisruptorMatchingService;

import java.util.function.Consumer;

/**
 * Feeds normalised {@link AggTrade}s into the Disruptor matching engine. It implements
 * {@code Consumer<AggTrade>}, so it plugs directly into {@link BinanceHistoricalDownloader#stream} and
 * {@link BinanceLiveClient#connect} — the same replayer drives both historical and live data.
 *
 * <p><b>How a public trade becomes engine activity.</b> Binance's public feed gives us trade prints,
 * not the underlying order-by-order book (that needs L3 data Binance doesn't publish). To exercise the
 * engine faithfully on price and size, we reconstruct each print as a crossing pair: a resting
 * <i>maker</i> limit order on the side that rested, immediately followed by the <i>taker</i> limit
 * order at the same price. The taker crosses the maker and the engine produces exactly that trade, at
 * the real price and quantity; the book nets back to empty, so memory stays bounded over millions of
 * trades. (A faithful continuous-book reconstruction is deferred — it needs L3 order data.)
 *
 * <p><b>Backpressure</b> is automatic: publishing claims a ring-buffer slot via {@code next()}, which
 * blocks if the single-writer consumer has fallen behind — the producer can never outrun the engine
 * or grow an unbounded queue.
 */
public final class MarketDataReplayer implements Consumer<AggTrade> {

    private final DisruptorMatchingService service;
    private final boolean measureLatency;
    private long nextOrderId = 1;
    private long replayedTrades;

    /**
     * @param service        the engine to feed
     * @param measureLatency stamp the taker order with an ingress timestamp so the engine records
     *                       end-to-end latency (set false for pure throughput runs)
     */
    public MarketDataReplayer(DisruptorMatchingService service, boolean measureLatency) {
        this.service = service;
        this.measureLatency = measureLatency;
    }

    @Override
    public void accept(AggTrade trade) {
        Side takerSide = trade.takerSide();
        Side makerSide = takerSide.opposite();
        long price = trade.priceTicks();
        long qty = trade.quantityUnits();

        // Maker rests first (untimed); taker crosses it and produces the trade (optionally timed).
        service.publishNewOrder(nextOrderId++, makerSide, OrderType.LIMIT, price, qty, 0);
        long ingress = measureLatency ? System.nanoTime() : 0;
        service.publishNewOrder(nextOrderId++, takerSide, OrderType.LIMIT, price, qty, ingress);

        replayedTrades++;
    }

    /** Number of source trades replayed so far (each produced one engine trade). */
    public long replayedTrades() {
        return replayedTrades;
    }
}
