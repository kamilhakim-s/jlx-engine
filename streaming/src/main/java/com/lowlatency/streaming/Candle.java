package com.lowlatency.streaming;

/**
 * The output of one window of analytics: an OHLC candle plus VWAP and trade-flow imbalance for one
 * symbol over one time window.
 *
 * <p>VWAP (volume-weighted average price) and imbalance are inherently fractional, so they're held as
 * {@code double} — but only for display/output. All aggregation upstream is done in integers
 * ({@link WindowStatsAccumulator}); the conversion to double happens once, at the end.
 *
 * <p><b>Trade-flow imbalance</b> here is {@code (buyVolume - sellVolume) / totalVolume} over the
 * window's prints — a measure of aggressor pressure derived from the trade tape. It is <i>not</i>
 * order-book (depth) imbalance, which would require L2 book data the public feed doesn't give us.
 */
public record Candle(
        String symbol,
        long windowStartMillis,
        long windowEndMillis,
        long open,
        long high,
        long low,
        long close,
        long volume,
        double vwap,
        long buyVolume,
        long sellVolume,
        double imbalance,
        long trades) {

    @Override
    public String toString() {
        return String.format(
                "%s [%d-%d] O=%d H=%d L=%d C=%d vol=%d vwap=%.2f imbalance=%+.3f trades=%d",
                symbol, windowStartMillis, windowEndMillis, open, high, low, close, volume, vwap,
                imbalance, trades);
    }
}
