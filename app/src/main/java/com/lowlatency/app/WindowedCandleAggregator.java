package com.lowlatency.app;

import com.lowlatency.streaming.Candle;
import com.lowlatency.streaming.TradeEvent;
import com.lowlatency.streaming.WindowStatsAccumulator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The analytics tier, run <b>in process</b> for the infra-free capstone. It consumes the
 * {@link TradeEvent}s the engine emits past the {@code AsyncTradeForwarder} seam and folds them into
 * tumbling <b>event-time</b> windows, emitting one {@link Candle} (OHLC + VWAP + volume + trade-flow
 * imbalance) per window — reusing Chunk 6's pure {@link WindowStatsAccumulator} maths verbatim.
 *
 * <p>This is the streaming tier's <i>shape</i> without its weight: in production this exact seam feeds
 * Kafka → Flink (Chunk 6's {@code EngineToKafkaApp}/{@code TradeAnalyticsJob}), where Flink supplies
 * watermarks, out-of-order tolerance, checkpointing and scale-out. Here, a single forwarder thread
 * delivers trades in order, so a one-line "the window advanced, emit it" is enough — no watermarks
 * needed. The boundary, not the framework, is the lesson.
 *
 * <p>Single-threaded by contract: only the forwarder thread calls {@link #accept}; the results are
 * published safely to the caller after the forwarder is closed and {@link #flush()} has run.
 */
public final class WindowedCandleAggregator implements Consumer<TradeEvent> {

    private final String symbol;
    private final long windowMillis;
    private final List<Candle> candles = new ArrayList<>();

    private WindowStatsAccumulator current;
    private long currentWindowStart = Long.MIN_VALUE;
    private long totalVolume;

    public WindowedCandleAggregator(String symbol, long windowMillis) {
        this.symbol = symbol;
        this.windowMillis = windowMillis;
    }

    @Override
    public void accept(TradeEvent event) {
        long windowStart = Math.floorDiv(event.timestampMillis(), windowMillis) * windowMillis;
        if (current == null) {
            currentWindowStart = windowStart;
            current = new WindowStatsAccumulator();
        } else if (windowStart > currentWindowStart) {
            emitCurrent();                       // the window advanced — close the previous one
            currentWindowStart = windowStart;
            current = new WindowStatsAccumulator();
        }
        current.add(event);
        totalVolume += event.quantityUnits();
    }

    /** Emits the final, still-open window. Call once after the forwarder has drained. */
    public void flush() {
        if (current != null && !current.isEmpty()) {
            emitCurrent();
            current = null;
        }
    }

    private void emitCurrent() {
        candles.add(current.toCandle(symbol, currentWindowStart, currentWindowStart + windowMillis));
    }

    /** Snapshot of the candles produced (safe to read after {@link #flush()}). */
    public List<Candle> candles() {
        return List.copyOf(candles);
    }

    /** Total matched volume seen by the analytics tier — should equal the engine's matched quantity. */
    public long totalVolume() {
        return totalVolume;
    }
}
