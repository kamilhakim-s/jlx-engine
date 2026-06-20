package com.lowlatency.engine;

/**
 * One price level, holding the FIFO queue of resting orders at that price as an <b>intrusive</b>
 * doubly-linked list — the links live in {@link Order#next}/{@link Order#prev} rather than in
 * separate node objects. That's the key allocation win over Chunk 1's {@code ArrayDeque}: enqueuing a
 * resting order allocates nothing, because the order <i>is</i> the node.
 *
 * <p>{@code totalQty} (aggregate resting size at this price) is maintained cooperatively: this class
 * adds on {@link #append} and subtracts on {@link #unlink}, while the engine subtracts each partial
 * fill directly. Pooled and reset via {@link #reset}.
 */
public final class PriceLevel {

    private long price;
    private long totalQty;
    private Order head; // oldest — matched first (time priority)
    private Order tail; // newest

    public void reset(long price) {
        this.price = price;
        this.totalQty = 0;
        this.head = null;
        this.tail = null;
    }

    /** Appends a resting order at the tail (newest), preserving time priority. */
    public void append(Order order) {
        order.prev = tail;
        order.next = null;
        if (tail != null) {
            tail.next = order;
        } else {
            head = order;
        }
        tail = order;
        totalQty += order.remaining();
    }

    /** Removes the fully-filled head order (its remaining is already 0, so no totalQty change). */
    public void removeHead() {
        Order h = head;
        head = h.next;
        if (head != null) {
            head.prev = null;
        } else {
            tail = null;
        }
        h.next = null;
        h.prev = null;
    }

    /** Removes an arbitrary order (cancellation path); subtracts its unfilled size from the total. */
    public void unlink(Order order) {
        Order p = order.prev;
        Order n = order.next;
        if (p != null) {
            p.next = n;
        } else {
            head = n;
        }
        if (n != null) {
            n.prev = p;
        } else {
            tail = p;
        }
        order.next = null;
        order.prev = null;
        totalQty -= order.remaining();
    }

    public Order head() {
        return head;
    }

    public boolean isEmpty() {
        return head == null;
    }

    public long price() {
        return price;
    }

    public long totalQty() {
        return totalQty;
    }

    /** Decreases the aggregate resting size by a partial fill (called by the engine on each match). */
    void reduceTotal(long qty) {
        totalQty -= qty;
    }
}
