package com.lowlatency.engine;

/**
 * A single order.
 *
 * <p><b>Integer money.</b> {@code price} and {@code quantity} are {@code long} integers, never
 * floating point. Exchanges represent prices in <i>ticks</i> (integer multiples of the minimum price
 * increment) and quantities in integer base units, because binary floating point cannot represent
 * decimal prices exactly and rounding errors are unacceptable when matching money. Pick a scale once
 * (e.g. price in 0.01 USDT ticks, quantity in 1e-8 BTC "satoshi" units) and keep everything integral.
 *
 * <p>The object is <b>mutable</b>: {@code remaining} shrinks as the order fills. Chunk 2 reuses this
 * same class from an object pool (hence {@link #reset}) and threads it through an intrusive linked
 * list (the package-private {@link #next}/{@link #prev} fields), so a resting order needs no extra
 * container node. The Chunk 1 engine simply ignores those two fields.
 */
public final class Order {

    private long id;
    private Side side;
    private OrderType type;
    private long price;       // in ticks; ignored for MARKET orders
    private long quantity;    // original order size
    private long remaining;   // unfilled size; 0 once fully matched

    /** Intrusive list links, used only by the Chunk 2 zero-allocation book. */
    Order next;
    Order prev;

    /** Creates an uninitialised order — call {@link #reset} before use (pool path). */
    public Order() {
    }

    public Order(long id, Side side, OrderType type, long price, long quantity) {
        reset(id, side, type, price, quantity);
    }

    /** Re-initialises this instance for reuse from a pool. */
    public void reset(long id, Side side, OrderType type, long price, long quantity) {
        this.id = id;
        this.side = side;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.remaining = quantity;
        this.next = null;
        this.prev = null;
    }

    /** Reduces the unfilled quantity by {@code qty} (called on each fill). */
    public void reduce(long qty) {
        remaining -= qty;
    }

    public long id() {
        return id;
    }

    public Side side() {
        return side;
    }

    public OrderType type() {
        return type;
    }

    public long price() {
        return price;
    }

    public long quantity() {
        return quantity;
    }

    public long remaining() {
        return remaining;
    }

    public boolean isFilled() {
        return remaining == 0;
    }

    @Override
    public String toString() {
        return "Order#" + id + "{" + side + " " + type + " " + remaining + "/" + quantity
                + " @ " + price + "}";
    }
}
