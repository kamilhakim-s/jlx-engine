package com.lowlatency.engine;

import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Correctness-first matching engine (Chunk 1): single-threaded, price-time priority.
 *
 * <p>The algorithm, for an incoming order, is the textbook continuous double auction:
 * <ol>
 *   <li>Walk the opposite side of the book from the best price inward.</li>
 *   <li>While the incoming order still has quantity and the resting price <i>crosses</i> (is
 *       acceptable to) the incoming order, match against resting orders in time order, emitting a
 *       trade at the <b>resting (maker) price</b> for each fill.</li>
 *   <li>Stop when the incoming order is filled, the book is exhausted, or (for a limit order) the
 *       next resting price no longer crosses.</li>
 *   <li>Any remainder of a {@code LIMIT} order rests in the book; a {@code MARKET} remainder is
 *       discarded.</li>
 * </ol>
 *
 * <p>"Crosses" means: an incoming buy can trade against an ask priced at or below its limit; an
 * incoming sell against a bid priced at or above its limit. A market order crosses any price.
 *
 * <p>Not in scope for Chunk 1: self-trade prevention (needs account identity, added later) and any
 * threading (the whole point of Chunk 3 is to add the single-writer concurrency layer <i>around</i>
 * a deliberately single-threaded core like this one).
 */
public final class MatchingEngine {

    private final OrderBook book;
    private final TradeHandler handler;
    private long tradeSequence;

    public MatchingEngine(OrderBook book, TradeHandler handler) {
        this.book = book;
        this.handler = handler;
    }

    /** Submits an order: matches it against the book, then rests any limit remainder. */
    public void submit(Order order) {
        match(order);
        if (order.remaining() > 0 && order.type() == OrderType.LIMIT) {
            book.addResting(order);
        }
    }

    /** Cancels a resting order by id; returns whether anything was removed. */
    public boolean cancel(long orderId) {
        return book.cancel(orderId);
    }

    public OrderBook book() {
        return book;
    }

    private void match(Order incoming) {
        NavigableMap<Long, Deque<Order>> levels = book.oppositeLevels(incoming.side());
        Iterator<Map.Entry<Long, Deque<Order>>> levelIt = levels.entrySet().iterator();

        while (incoming.remaining() > 0 && levelIt.hasNext()) {
            Map.Entry<Long, Deque<Order>> entry = levelIt.next();
            long restingPrice = entry.getKey();

            if (!crosses(incoming, restingPrice)) {
                break; // best remaining price is unacceptable — no further matches possible
            }

            Deque<Order> level = entry.getValue();
            while (incoming.remaining() > 0 && !level.isEmpty()) {
                Order maker = level.peekFirst();
                long fillQty = Math.min(incoming.remaining(), maker.remaining());

                handler.onTrade(new TradeRecord(
                        incoming.id(), maker.id(), incoming.side(), restingPrice, fillQty, tradeSequence++));

                incoming.reduce(fillQty);
                maker.reduce(fillQty);

                if (maker.isFilled()) {
                    level.pollFirst();
                    book.forgetResting(maker.id());
                }
            }
            if (level.isEmpty()) {
                levelIt.remove();
            }
        }
    }

    /** Whether {@code incoming} is willing to trade at {@code restingPrice}. */
    private static boolean crosses(Order incoming, long restingPrice) {
        if (incoming.type() == OrderType.MARKET) {
            return true;
        }
        return incoming.side() == Side.BUY
                ? restingPrice <= incoming.price()
                : restingPrice >= incoming.price();
    }
}
