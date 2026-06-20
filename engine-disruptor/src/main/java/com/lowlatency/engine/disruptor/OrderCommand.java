package com.lowlatency.engine.disruptor;

import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;

/**
 * The mutable event that travels through the Disruptor ring buffer. One instance per ring slot is
 * created <b>once</b> at start-up by the event factory and then <b>reused forever</b> — publishers
 * overwrite the fields in place and consumers read them. This is the heart of the Disruptor's
 * mechanical sympathy: a fixed array of pre-allocated events means no per-message allocation and a
 * cache-friendly, contiguous memory layout (compare a linked-node queue, which allocates and scatters
 * nodes across the heap).
 *
 * <p>A command is either a {@link CommandType#NEW_ORDER} (carrying the full order fields) or a
 * {@link CommandType#CANCEL} (carrying just the order id). {@code ingressNanos} records when the
 * publisher <i>intended</i> to submit the command, so the consumer can measure honest end-to-end
 * latency (timed from the intended send instant — the coordinated-omission-safe approach from
 * {@code docs/00-foundations.md}).
 */
public final class OrderCommand {

    private CommandType type;
    private long orderId;
    private Side side;
    private OrderType orderType;
    private long price;
    private long quantity;
    private long ingressNanos;

    /** Populates this slot as a new-order command. */
    public void setNewOrder(long orderId, Side side, OrderType orderType,
                            long price, long quantity, long ingressNanos) {
        this.type = CommandType.NEW_ORDER;
        this.orderId = orderId;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.quantity = quantity;
        this.ingressNanos = ingressNanos;
    }

    /** Populates this slot as a cancel command. */
    public void setCancel(long orderId, long ingressNanos) {
        this.type = CommandType.CANCEL;
        this.orderId = orderId;
        this.ingressNanos = ingressNanos;
    }

    public CommandType type() {
        return type;
    }

    public long orderId() {
        return orderId;
    }

    public Side side() {
        return side;
    }

    public OrderType orderType() {
        return orderType;
    }

    public long price() {
        return price;
    }

    public long quantity() {
        return quantity;
    }

    public long ingressNanos() {
        return ingressNanos;
    }
}
