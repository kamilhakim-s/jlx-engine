package com.lowlatency.engine;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Correctness-first limit order book (Chunk 1).
 *
 * <p>This is the deliberately simple, idiomatic-Java version. It models <b>price-time priority</b>
 * with the most obvious data structures:
 *
 * <ul>
 *   <li>Each side is a {@link TreeMap} from price to a FIFO queue of orders at that price. The map is
 *       sorted so the <i>best</i> price is always first: bids descending (highest bid first), asks
 *       ascending (lowest ask first). That gives <b>price priority</b>.</li>
 *   <li>Within a price level, an {@link ArrayDeque} preserves arrival order, giving <b>time
 *       priority</b> (first in, first matched).</li>
 *   <li>A {@code HashMap} from order id to order enables O(1) cancel lookup.</li>
 * </ul>
 *
 * <p>These structures box every {@code long} price into a {@link Long} and allocate tree/deque nodes
 * on every insert — exactly the overhead Chunk 2 removes. We keep this version forever as the
 * readable reference and as a correctness oracle the fast engine is checked against.
 *
 * <p>This class only stores resting liquidity and answers queries; the matching logic lives in
 * {@link MatchingEngine}.
 */
public final class OrderBook {

    /** Bids: highest price first. */
    private final NavigableMap<Long, Deque<Order>> bids = new TreeMap<>(Collections.reverseOrder());
    /** Asks: lowest price first. */
    private final NavigableMap<Long, Deque<Order>> asks = new TreeMap<>();
    /** All resting orders by id, for cancel lookup. */
    private final Map<Long, Order> byId = new HashMap<>();

    /** The price-ordered levels an incoming order on {@code side} matches against. */
    NavigableMap<Long, Deque<Order>> oppositeLevels(Side side) {
        return side == Side.BUY ? asks : bids;
    }

    /** The price-ordered levels an order on {@code side} rests in. */
    private NavigableMap<Long, Deque<Order>> restingLevels(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    /** Adds the (partially filled) order to the book as resting liquidity. */
    void addResting(Order order) {
        restingLevels(order.side())
                .computeIfAbsent(order.price(), p -> new ArrayDeque<>())
                .addLast(order);
        byId.put(order.id(), order);
    }

    /**
     * Forgets a resting order's id once it has been fully filled. The caller (the engine) is
     * responsible for having already removed it from its price-level deque and, if that deque is now
     * empty, for pruning the level via the iterator it is traversing.
     */
    void forgetResting(long orderId) {
        byId.remove(orderId);
    }

    /**
     * Cancels a resting order by id.
     *
     * @return {@code true} if the order was resting and is now removed; {@code false} if unknown
     *         (already filled, never seen, or a market order that never rested).
     */
    public boolean cancel(long orderId) {
        Order order = byId.remove(orderId);
        if (order == null) {
            return false;
        }
        NavigableMap<Long, Deque<Order>> levels = restingLevels(order.side());
        Deque<Order> level = levels.get(order.price());
        if (level != null) {
            level.remove(order);
            if (level.isEmpty()) {
                levels.remove(order.price());
            }
        }
        return true;
    }

    /** Best (highest) bid price, or {@code -1} if there are no bids. */
    public long bestBid() {
        return bids.isEmpty() ? -1 : bids.firstKey();
    }

    /** Best (lowest) ask price, or {@code -1} if there are no asks. */
    public long bestAsk() {
        return asks.isEmpty() ? -1 : asks.firstKey();
    }

    /** Total resting quantity at a given price on a given side (0 if none). */
    public long quantityAt(Side side, long price) {
        Deque<Order> level = restingLevels(side).get(price);
        if (level == null) {
            return 0;
        }
        long total = 0;
        for (Order o : level) {
            total += o.remaining();
        }
        return total;
    }

    /** True if there is no resting liquidity on either side. */
    public boolean isEmpty() {
        return bids.isEmpty() && asks.isEmpty();
    }
}
