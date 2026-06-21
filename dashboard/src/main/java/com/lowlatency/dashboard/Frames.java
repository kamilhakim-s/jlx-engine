package com.lowlatency.dashboard;

/**
 * The wire protocol between the backend and the dashboard SPA: small immutable frames serialised to JSON
 * and pushed over Server-Sent Events. Each is broadcast under a named SSE event the frontend dispatches on.
 *
 * <p>Frame rates are deliberately bounded so the browser is never flooded even when the engine runs at
 * hundreds of thousands of orders/sec: {@code metrics} and {@code tape} fire on a fixed ~250 ms timer,
 * {@code candle} fires once per closed window, and {@code status} only on change. Individual trades are
 * NOT streamed one-per-event under load — they are sampled into the {@code tape} batch.
 */
final class Frames {

    /** Connection/control state. Event name: {@code status}. */
    record StatusFrame(String symbol, String source, boolean stress, long stressRate,
                       long uptimeMillis, boolean live) {
    }

    /** The low-latency hero frame: latency percentiles + throughput + GC/allocation. Event: {@code metrics}. */
    record MetricsFrame(
            long ts,
            // latency percentiles, nanoseconds
            long p50, long p99, long p999, long p9999, long max, long count,
            // throughput
            double ordersPerSec, double tradesPerSec, long processedTotal, long tradeTotal,
            // GC / allocation over the sample interval
            long gcCollections, long gcPauseMillis, long allocBytes, double allocBytesPerSec) {
    }

    /** One executed trade for the tape. Streamed in batches inside {@link TapeFrame}. */
    record TradeRow(long seq, long price, long qty, boolean takerBuy, long ts) {
    }

    /** A batch of the most recent trades. Event name: {@code tape}. */
    record TapeFrame(long ts, java.util.List<TradeRow> trades) {
    }

    /** One closed analytics window (OHLC + VWAP + imbalance). Event name: {@code candle}. */
    record CandleFrame(long start, long end, long open, long high, long low, long close,
                       long volume, double vwap, double imbalance, long trades) {
    }

    private Frames() {
    }
}
