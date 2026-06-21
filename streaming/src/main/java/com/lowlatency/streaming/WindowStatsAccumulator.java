package com.lowlatency.streaming;

/**
 * Accumulates OHLC, volume, VWAP, and buy/sell volume for the trades falling in one window. This is
 * <b>plain Java with no Flink dependency</b> on purpose: the analytics arithmetic is the part most
 * worth getting right and unit-testing, so it lives here, independent of the streaming runtime. The
 * Flink {@link WindowStatsAggregator} just drives this class.
 *
 * <p>All sums are integer: {@code vwapNumerator} accumulates {@code price * quantity}, and VWAP is
 * {@code vwapNumerator / volume} computed as a double only when producing the final {@link Candle}.
 * (For very high-volume windows {@code vwapNumerator} could approach {@code long} range; a production
 * system would scale down or use a wider type — noted, not handled here.)
 */
public final class WindowStatsAccumulator {

    private boolean empty = true;
    private long open;
    private long high;
    private long low;
    private long close;
    private long volume;
    private long vwapNumerator; // sum of price*quantity
    private long buyVolume;
    private long sellVolume;
    private long trades;

    public void add(long price, long quantity, boolean takerBuy) {
        if (empty) {
            open = high = low = price;
            empty = false;
        } else {
            if (price > high) {
                high = price;
            }
            if (price < low) {
                low = price;
            }
        }
        close = price;
        volume += quantity;
        vwapNumerator += price * quantity;
        if (takerBuy) {
            buyVolume += quantity;
        } else {
            sellVolume += quantity;
        }
        trades++;
    }

    public void add(TradeEvent event) {
        add(event.priceTicks(), event.quantityUnits(), event.takerBuy());
    }

    /** Merges another accumulator into this one (used by Flink for some window types). */
    public WindowStatsAccumulator merge(WindowStatsAccumulator other) {
        if (other.empty) {
            return this;
        }
        if (empty) {
            open = other.open;
            high = other.high;
            low = other.low;
            empty = false;
        } else {
            if (other.high > high) {
                high = other.high;
            }
            if (other.low < low) {
                low = other.low;
            }
        }
        close = other.close; // best-effort; tumbling windows don't merge panes
        volume += other.volume;
        vwapNumerator += other.vwapNumerator;
        buyVolume += other.buyVolume;
        sellVolume += other.sellVolume;
        trades += other.trades;
        return this;
    }

    public double vwap() {
        return volume == 0 ? 0.0 : (double) vwapNumerator / volume;
    }

    public double imbalance() {
        long total = buyVolume + sellVolume;
        return total == 0 ? 0.0 : (double) (buyVolume - sellVolume) / total;
    }

    public Candle toCandle(String symbol, long windowStartMillis, long windowEndMillis) {
        return new Candle(symbol, windowStartMillis, windowEndMillis,
                open, high, low, close, volume, vwap(), buyVolume, sellVolume, imbalance(), trades);
    }

    public boolean isEmpty() {
        return empty;
    }

    public long volume() {
        return volume;
    }

    public long trades() {
        return trades;
    }
}
