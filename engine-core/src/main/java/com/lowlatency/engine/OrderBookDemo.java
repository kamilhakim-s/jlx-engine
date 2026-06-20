package com.lowlatency.engine;

/**
 * A scripted walk-through of the Chunk 1 engine. Run with {@code ./gradlew :engine-core:run}.
 *
 * <p>It builds a small book, then sends an aggressive order that sweeps multiple price levels, so you
 * can see price-time priority, partial fills, multi-level matching, resting, and cancellation in the
 * printed output. Prices are integer ticks (think hundredths of a USDT); quantities are integer units.
 */
public final class OrderBookDemo {

    public static void main(String[] args) {
        OrderBook book = new OrderBook();
        MatchingEngine engine = new MatchingEngine(book, OrderBookDemo::printTrade);

        System.out.println("=== Building the book (resting limit orders, no crosses yet) ===");
        // Resting asks (sellers) at 101, 102, 103.
        submit(engine, new Order(1, Side.SELL, OrderType.LIMIT, 103, 5));
        submit(engine, new Order(2, Side.SELL, OrderType.LIMIT, 101, 3));
        submit(engine, new Order(3, Side.SELL, OrderType.LIMIT, 101, 2)); // same level as #2, later in time
        submit(engine, new Order(4, Side.SELL, OrderType.LIMIT, 102, 4));
        // Resting bids (buyers) at 99, 98.
        submit(engine, new Order(5, Side.BUY, OrderType.LIMIT, 99, 10));
        submit(engine, new Order(6, Side.BUY, OrderType.LIMIT, 98, 7));
        printBook(book);

        System.out.println("\n=== Aggressive BUY limit @102 for 8 units (sweeps 101 then 102) ===");
        // Should fill: 3 @101 (order #2, time priority), 2 @101 (order #3), 3 @102 (order #4, partial).
        submit(engine, new Order(7, Side.BUY, OrderType.LIMIT, 102, 8));
        printBook(book);

        System.out.println("\n=== MARKET SELL for 12 units (hits bids 99 then 98) ===");
        // Fills 10 @99 (#5) then 2 @98 (#6, partial); no remainder rests (market orders never rest).
        submit(engine, new Order(8, Side.SELL, OrderType.MARKET, 0, 12));
        printBook(book);

        System.out.println("\n=== Cancel order #1 (resting ask @103) ===");
        System.out.println("cancel(1) -> " + engine.cancel(1));
        System.out.println("cancel(999) -> " + engine.cancel(999) + "  (unknown id)");
        printBook(book);
    }

    private static void submit(MatchingEngine engine, Order order) {
        System.out.println("submit " + order);
        engine.submit(order);
    }

    private static void printTrade(Trade t) {
        System.out.printf("  TRADE seq=%d  taker#%d %s  maker#%d  qty=%d @ %d%n",
                t.sequence(), t.takerOrderId(), t.takerSide(), t.makerOrderId(), t.quantity(), t.price());
    }

    private static void printBook(OrderBook book) {
        System.out.printf("  book: bestBid=%s bestAsk=%s%s%n",
                priceOrDash(book.bestBid()),
                priceOrDash(book.bestAsk()),
                book.isEmpty() ? "  (empty)" : "");
    }

    private static String priceOrDash(long price) {
        return price < 0 ? "-" : Long.toString(price);
    }

    private OrderBookDemo() {
    }
}
