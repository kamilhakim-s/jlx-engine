package com.lowlatency.dashboard;

import com.lowlatency.streaming.Candle;
import com.lowlatency.streaming.TradeEvent;
import com.lowlatency.streaming.WindowStatsAccumulator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/**
 * The dashboard's market-data aggregator, living <b>past the {@code AsyncTradeForwarder} seam</b> — it is
 * invoked on the forwarder thread, never the engine thread, so nothing here can slow matching. It does two
 * jobs with each trade:
 *
 * <ul>
 *   <li>folds it into a tumbling event-time window via Chunk 6's {@link WindowStatsAccumulator}, emitting a
 *       {@link Frames.CandleFrame} when the window closes (OHLC + VWAP + imbalance);</li>
 *   <li>keeps a small bounded buffer of the most recent trades for the live "tape" (sampled by
 *       {@link MetricsSampler}, not streamed one-per-event — a trade-per-frame would flood the browser at
 *       hundreds of thousands of trades/sec).</li>
 * </ul>
 */
final class MarketState implements Consumer<TradeEvent> {

    private final long windowMillis;
    private final Consumer<Frames.CandleFrame> candleSink;
    // Bounded "recent trades" ring; the sampler snapshots it. Keep-newest on overflow.
    private final ArrayBlockingQueue<Frames.TradeRow> recent = new ArrayBlockingQueue<>(64);

    private WindowStatsAccumulator current;
    private long currentWindowStart = Long.MIN_VALUE;
    private volatile long lastPrice; // most recent print; lets the load generator stay near the live price

    MarketState(long windowMillis, Consumer<Frames.CandleFrame> candleSink) {
        this.windowMillis = windowMillis;
        this.candleSink = candleSink;
    }

    @Override
    public void accept(TradeEvent event) {
        long windowStart = Math.floorDiv(event.timestampMillis(), windowMillis) * windowMillis;
        if (current == null) {
            currentWindowStart = windowStart;
            current = new WindowStatsAccumulator();
        } else if (windowStart > currentWindowStart) {
            emit(currentWindowStart);            // the window advanced — close & publish the previous one
            currentWindowStart = windowStart;
            current = new WindowStatsAccumulator();
        }
        current.add(event);
        lastPrice = event.priceTicks();

        Frames.TradeRow row = new Frames.TradeRow(
                event.sequence(), event.priceTicks(), event.quantityUnits(),
                event.takerBuy(), event.timestampMillis());
        if (!recent.offer(row)) {
            recent.poll();                       // full → evict oldest, keep the newest
            recent.offer(row);
        }
    }

    /** A snapshot of the most recent trades (newest last). Safe to call from the sampler thread. */
    List<Frames.TradeRow> recentTrades() {
        return new ArrayList<>(recent);
    }

    /** Most recent print price in ticks, or 0 before any trade. */
    long lastPrice() {
        return lastPrice;
    }

    private void emit(long windowStart) {
        if (current.isEmpty()) {
            return;
        }
        Candle c = current.toCandle("", windowStart, windowStart + windowMillis);
        candleSink.accept(new Frames.CandleFrame(
                c.windowStartMillis(), c.windowEndMillis(), c.open(), c.high(), c.low(), c.close(),
                c.volume(), c.vwap(), c.imbalance(), c.trades()));
    }
}
