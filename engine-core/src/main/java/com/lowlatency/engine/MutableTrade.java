package com.lowlatency.engine;

/**
 * A single reusable {@link Trade} instance. The Chunk 2 engine overwrites this object and passes the
 * same reference to {@link TradeHandler#onTrade} for every trade, so emitting a trade allocates
 * nothing (compare Chunk 1, which {@code new}s a {@link TradeRecord} per trade).
 *
 * <p><b>Contract:</b> the reference is only valid for the duration of the {@code onTrade} call. A
 * handler that needs to keep a trade must copy the fields out (as {@link CollectingTradeHandler}
 * does). This "flyweight passed to a callback" pattern is everywhere in low-latency Java.
 */
public final class MutableTrade implements Trade {

    private long takerOrderId;
    private long makerOrderId;
    private Side takerSide;
    private long price;
    private long quantity;
    private long sequence;

    void set(long takerOrderId, long makerOrderId, Side takerSide, long price, long quantity, long sequence) {
        this.takerOrderId = takerOrderId;
        this.makerOrderId = makerOrderId;
        this.takerSide = takerSide;
        this.price = price;
        this.quantity = quantity;
        this.sequence = sequence;
    }

    @Override
    public long takerOrderId() {
        return takerOrderId;
    }

    @Override
    public long makerOrderId() {
        return makerOrderId;
    }

    @Override
    public Side takerSide() {
        return takerSide;
    }

    @Override
    public long price() {
        return price;
    }

    @Override
    public long quantity() {
        return quantity;
    }

    @Override
    public long sequence() {
        return sequence;
    }
}
