package com.lowlatency.engine;

import it.unimi.dsi.fastutil.longs.Long2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.longs.LongComparators;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * Zero-allocation order book (Chunk 2) — same semantics as {@link OrderBook}, different innards.
 *
 * <p>Every structure here is keyed by a primitive {@code long}, so there is <b>no autoboxing</b> of
 * prices or ids on the hot path (Chunk 1's {@code TreeMap<Long,…>} boxed a {@link Long} on every
 * lookup):
 *
 * <ul>
 *   <li>{@code asks}/{@code bids}: fastutil {@link Long2ObjectRBTreeMap} from price → {@link PriceLevel},
 *       sorted so the best price is {@code firstLongKey()}. Bids use {@code OPPOSITE_COMPARATOR} for
 *       descending order (highest bid first); asks use natural ascending order.</li>
 *   <li>{@code byId}: Agrona {@link Long2ObjectHashMap} from order id → {@link Order}, for O(1) cancel
 *       with no boxing.</li>
 * </ul>
 *
 * <p>Within a level, orders form an intrusive FIFO list (see {@link PriceLevel}). Reading the touch
 * via {@code firstLongKey()} + {@code get(long)} touches no iterator and allocates nothing — that is
 * what makes the match path allocation-free.
 */
public final class FastOrderBook {

    final Long2ObjectRBTreeMap<PriceLevel> bids = new Long2ObjectRBTreeMap<>(LongComparators.OPPOSITE_COMPARATOR);
    final Long2ObjectRBTreeMap<PriceLevel> asks = new Long2ObjectRBTreeMap<>();
    final Long2ObjectHashMap<Order> byId = new Long2ObjectHashMap<>();

    /** Levels an incoming order on {@code side} matches against. */
    Long2ObjectRBTreeMap<PriceLevel> oppositeLevels(Side side) {
        return side == Side.BUY ? asks : bids;
    }

    /** Levels an order on {@code side} rests in. */
    Long2ObjectRBTreeMap<PriceLevel> restingLevels(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    public long bestBid() {
        return bids.isEmpty() ? -1 : bids.firstLongKey();
    }

    public long bestAsk() {
        return asks.isEmpty() ? -1 : asks.firstLongKey();
    }

    public long quantityAt(Side side, long price) {
        PriceLevel level = restingLevels(side).get(price);
        return level == null ? 0 : level.totalQty();
    }

    public boolean isEmpty() {
        return bids.isEmpty() && asks.isEmpty();
    }
}
