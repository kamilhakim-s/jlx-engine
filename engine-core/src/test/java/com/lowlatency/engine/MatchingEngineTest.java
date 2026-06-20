package com.lowlatency.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Behavioural tests for the Chunk 1 correctness-first matching engine. */
class MatchingEngineTest {

    private OrderBook book;
    private CollectingTradeHandler handler;
    private MatchingEngine engine;
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        book = new OrderBook();
        handler = new CollectingTradeHandler();
        engine = new MatchingEngine(book, handler);
    }

    private Order buy(OrderType type, long price, long qty) {
        return new Order(nextId++, Side.BUY, type, price, qty);
    }

    private Order sell(OrderType type, long price, long qty) {
        return new Order(nextId++, Side.SELL, type, price, qty);
    }

    @Nested
    @DisplayName("resting (no cross)")
    class Resting {

        @Test
        void nonCrossingLimitsJustRest() {
            engine.submit(sell(OrderType.LIMIT, 101, 5));
            engine.submit(buy(OrderType.LIMIT, 99, 5));

            assertThat(handler.trades()).isEmpty();
            assertThat(book.bestAsk()).isEqualTo(101);
            assertThat(book.bestBid()).isEqualTo(99);
        }

        @Test
        void emptyBookReportsNoTouch() {
            assertThat(book.bestBid()).isEqualTo(-1);
            assertThat(book.bestAsk()).isEqualTo(-1);
            assertThat(book.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("crossing & fills")
    class Crossing {

        @Test
        void fullFillAgainstSingleResting() {
            engine.submit(sell(OrderType.LIMIT, 100, 5));   // id 1 rests
            engine.submit(buy(OrderType.LIMIT, 100, 5));    // id 2 crosses

            assertThat(handler.trades()).singleElement().satisfies(t -> {
                assertThat(t.takerOrderId()).isEqualTo(2);
                assertThat(t.makerOrderId()).isEqualTo(1);
                assertThat(t.takerSide()).isEqualTo(Side.BUY);
                assertThat(t.price()).isEqualTo(100);
                assertThat(t.quantity()).isEqualTo(5);
            });
            assertThat(book.isEmpty()).isTrue();
        }

        @Test
        void tradeExecutesAtRestingMakerPrice() {
            engine.submit(sell(OrderType.LIMIT, 100, 5));   // maker @100
            engine.submit(buy(OrderType.LIMIT, 105, 5));    // willing to pay up to 105

            // Price improvement: buyer pays the maker's 100, not its own 105.
            assertThat(handler.trades()).singleElement()
                    .satisfies(t -> assertThat(t.price()).isEqualTo(100));
        }

        @Test
        void aggressorPartiallyFilledThenRestsRemainder() {
            engine.submit(sell(OrderType.LIMIT, 100, 3));   // only 3 available
            engine.submit(buy(OrderType.LIMIT, 100, 8));    // wants 8

            assertThat(handler.trades()).singleElement()
                    .satisfies(t -> assertThat(t.quantity()).isEqualTo(3));
            // Remaining 5 rests as the new best bid.
            assertThat(book.bestBid()).isEqualTo(100);
            assertThat(book.quantityAt(Side.BUY, 100)).isEqualTo(5);
            assertThat(book.bestAsk()).isEqualTo(-1);
        }

        @Test
        void restingMakerPartiallyFilledStaysOnBook() {
            engine.submit(sell(OrderType.LIMIT, 100, 10));  // big maker
            engine.submit(buy(OrderType.LIMIT, 100, 4));    // small taker

            assertThat(handler.trades()).singleElement()
                    .satisfies(t -> assertThat(t.quantity()).isEqualTo(4));
            assertThat(book.bestAsk()).isEqualTo(100);
            assertThat(book.quantityAt(Side.SELL, 100)).isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("price-time priority")
    class Priority {

        @Test
        void timePriorityWithinALevelIsFifo() {
            Order first = sell(OrderType.LIMIT, 100, 2);   // id 1, earlier
            Order second = sell(OrderType.LIMIT, 100, 2);  // id 2, later, same price
            engine.submit(first);
            engine.submit(second);

            engine.submit(buy(OrderType.LIMIT, 100, 3));   // id 3 takes 2 from #1, 1 from #2

            assertThat(handler.trades()).extracting(Trade::makerOrderId, Trade::quantity)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(1L, 2L),
                            org.assertj.core.groups.Tuple.tuple(2L, 1L));
        }

        @Test
        void pricePrioritySweepsBestLevelsFirst() {
            engine.submit(sell(OrderType.LIMIT, 102, 5));  // id 1
            engine.submit(sell(OrderType.LIMIT, 100, 5));  // id 2 (better price)
            engine.submit(sell(OrderType.LIMIT, 101, 5));  // id 3

            engine.submit(buy(OrderType.LIMIT, 102, 12));  // sweeps 100, 101, then 2 @102

            assertThat(handler.trades()).extracting(Trade::price, Trade::makerOrderId, Trade::quantity)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(100L, 2L, 5L),
                            org.assertj.core.groups.Tuple.tuple(101L, 3L, 5L),
                            org.assertj.core.groups.Tuple.tuple(102L, 1L, 2L));
            assertThat(book.quantityAt(Side.SELL, 102)).isEqualTo(3);
        }

        @Test
        void limitStopsWhenNextPriceNoLongerCrosses() {
            engine.submit(sell(OrderType.LIMIT, 100, 5));
            engine.submit(sell(OrderType.LIMIT, 105, 5));

            engine.submit(buy(OrderType.LIMIT, 100, 8)); // only the 100 level is acceptable

            assertThat(handler.trades()).singleElement()
                    .satisfies(t -> assertThat(t.quantity()).isEqualTo(5));
            // 3 unfilled rest at 100; the 105 ask is untouched.
            assertThat(book.bestBid()).isEqualTo(100);
            assertThat(book.quantityAt(Side.SELL, 105)).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("market orders")
    class Market {

        @Test
        void marketOrderIgnoresPriceAndSweeps() {
            engine.submit(sell(OrderType.LIMIT, 100, 4));
            engine.submit(sell(OrderType.LIMIT, 200, 4));

            engine.submit(buy(OrderType.MARKET, 0, 6)); // ignores price, takes 4@100 then 2@200

            assertThat(handler.trades()).extracting(Trade::price, Trade::quantity)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(100L, 4L),
                            org.assertj.core.groups.Tuple.tuple(200L, 2L));
        }

        @Test
        void marketRemainderIsDiscardedNotRested() {
            engine.submit(sell(OrderType.LIMIT, 100, 3));

            engine.submit(buy(OrderType.MARKET, 0, 10)); // only 3 available

            assertThat(handler.trades()).singleElement()
                    .satisfies(t -> assertThat(t.quantity()).isEqualTo(3));
            // The 7-unit remainder does NOT rest — a market order never sits on the book.
            assertThat(book.isEmpty()).isTrue();
        }

        @Test
        void marketOrderIntoEmptyBookDoesNothing() {
            engine.submit(buy(OrderType.MARKET, 0, 5));

            assertThat(handler.trades()).isEmpty();
            assertThat(book.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("cancellation")
    class Cancellation {

        @Test
        void cancelRemovesRestingOrder() {
            Order resting = sell(OrderType.LIMIT, 100, 5); // id 1
            engine.submit(resting);

            assertThat(engine.cancel(1)).isTrue();
            assertThat(book.isEmpty()).isTrue();

            // A later buy that would have matched now finds nothing.
            engine.submit(buy(OrderType.LIMIT, 100, 5));
            assertThat(handler.trades()).isEmpty();
            assertThat(book.bestBid()).isEqualTo(100); // it rests instead
        }

        @Test
        void cancelUnknownIdIsNoOp() {
            assertThat(engine.cancel(42)).isFalse();
        }

        @Test
        void cannotCancelAFilledOrder() {
            engine.submit(sell(OrderType.LIMIT, 100, 5)); // id 1
            engine.submit(buy(OrderType.LIMIT, 100, 5));  // fully fills id 1

            assertThat(engine.cancel(1)).isFalse();
        }

        @Test
        void cancelOneOfTwoAtSameLevelLeavesTheOther() {
            engine.submit(sell(OrderType.LIMIT, 100, 2)); // id 1
            engine.submit(sell(OrderType.LIMIT, 100, 3)); // id 2

            assertThat(engine.cancel(1)).isTrue();
            assertThat(book.quantityAt(Side.SELL, 100)).isEqualTo(3);

            engine.submit(buy(OrderType.LIMIT, 100, 3));
            assertThat(handler.trades()).singleElement().satisfies(t -> {
                assertThat(t.makerOrderId()).isEqualTo(2L); // id 1 is gone
                assertThat(t.quantity()).isEqualTo(3L);
            });
        }
    }

    @Test
    @DisplayName("sequence numbers are monotonic across trades")
    void sequenceNumbersAreMonotonic() {
        engine.submit(sell(OrderType.LIMIT, 100, 1));
        engine.submit(sell(OrderType.LIMIT, 100, 1));
        engine.submit(sell(OrderType.LIMIT, 100, 1));

        engine.submit(buy(OrderType.LIMIT, 100, 3));

        List<Trade> trades = handler.trades();
        assertThat(trades).extracting(Trade::sequence).containsExactly(0L, 1L, 2L);
    }
}
