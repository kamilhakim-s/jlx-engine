package com.lowlatency.engine;

import it.unimi.dsi.fastutil.longs.Long2ObjectRBTreeMap;

/**
 * Zero-allocation matching engine (Chunk 2). Behaviourally identical to {@link MatchingEngine} — the
 * randomised {@code EngineEquivalenceTest} proves it produces byte-for-byte the same trades — but the
 * match path allocates nothing:
 *
 * <ul>
 *   <li>the touch is read with {@code firstLongKey()} + {@code get(long)} (no iterator, no boxing);</li>
 *   <li>each trade reuses one {@link MutableTrade} flyweight instead of {@code new}-ing a record;</li>
 *   <li>filled orders and emptied price levels are returned to {@link ObjectPool}s for reuse.</li>
 * </ul>
 *
 * <p><b>Ownership:</b> the engine owns an order once it is submitted. The caller acquires an
 * {@link Order} from {@link #orderPool()}, fills it in, and submits it; the engine recycles it when it
 * is fully filled or cancelled, or keeps it while it rests. (Inserting a brand-new price level still
 * allocates one node inside the fastutil tree — that's off the hot match path, which only consumes
 * existing levels.)
 */
public final class FastMatchingEngine {

    private final FastOrderBook book;
    private final TradeHandler handler;
    private final ObjectPool<Order> orderPool;
    private final ObjectPool<PriceLevel> levelPool;
    private final MutableTrade trade = new MutableTrade();
    private long tradeSequence;

    public FastMatchingEngine(FastOrderBook book, TradeHandler handler, int expectedOrders) {
        this.book = book;
        this.handler = handler;
        this.orderPool = new ObjectPool<>(Order::new, expectedOrders);
        this.levelPool = new ObjectPool<>(PriceLevel::new, Math.max(16, expectedOrders / 8));
    }

    /** Pool callers draw incoming {@link Order} instances from, so submission allocates nothing. */
    public ObjectPool<Order> orderPool() {
        return orderPool;
    }

    public FastOrderBook book() {
        return book;
    }

    public void submit(Order order) {
        match(order);
        if (order.remaining() > 0 && order.type() == OrderType.LIMIT) {
            rest(order);
        } else {
            // Fully filled, or a market order's discarded remainder — recycle the order object.
            orderPool.release(order);
        }
    }

    public boolean cancel(long orderId) {
        Order order = book.byId.remove(orderId);
        if (order == null) {
            return false;
        }
        Long2ObjectRBTreeMap<PriceLevel> levels = book.restingLevels(order.side());
        PriceLevel level = levels.get(order.price());
        if (level != null) {
            level.unlink(order);
            if (level.isEmpty()) {
                levels.remove(order.price());
                levelPool.release(level);
            }
        }
        orderPool.release(order);
        return true;
    }

    private void match(Order incoming) {
        Long2ObjectRBTreeMap<PriceLevel> levels = book.oppositeLevels(incoming.side());

        while (incoming.remaining() > 0 && !levels.isEmpty()) {
            long bestPrice = levels.firstLongKey();          // best price, no boxing
            if (!crosses(incoming, bestPrice)) {
                break;
            }
            PriceLevel level = levels.get(bestPrice);        // primitive get, no boxing

            while (incoming.remaining() > 0 && !level.isEmpty()) {
                Order maker = level.head();
                long fillQty = Math.min(incoming.remaining(), maker.remaining());

                trade.set(incoming.id(), maker.id(), incoming.side(), bestPrice, fillQty, tradeSequence++);
                handler.onTrade(trade);                      // reused flyweight, no allocation

                incoming.reduce(fillQty);
                maker.reduce(fillQty);
                level.reduceTotal(fillQty);

                if (maker.isFilled()) {
                    level.removeHead();
                    book.byId.remove(maker.id());
                    orderPool.release(maker);
                }
            }
            if (level.isEmpty()) {
                levels.remove(bestPrice);
                levelPool.release(level);
            }
        }
    }

    private void rest(Order order) {
        Long2ObjectRBTreeMap<PriceLevel> levels = book.restingLevels(order.side());
        PriceLevel level = levels.get(order.price());
        if (level == null) {
            level = levelPool.acquire();
            level.reset(order.price());
            levels.put(order.price(), level);
        }
        level.append(order);
        book.byId.put(order.id(), order);
    }

    private static boolean crosses(Order incoming, long restingPrice) {
        if (incoming.type() == OrderType.MARKET) {
            return true;
        }
        return incoming.side() == Side.BUY
                ? restingPrice <= incoming.price()
                : restingPrice >= incoming.price();
    }
}
