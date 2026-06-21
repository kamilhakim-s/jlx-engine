package com.lowlatency.dashboard;

import com.lowlatency.streaming.TradeEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the past-the-seam aggregator: tumbling candles, recent-trade buffer, last price. */
class MarketStateTest {

    private static TradeEvent trade(long seq, long price, long qty, boolean buy, long ts) {
        return new TradeEvent("BTCUSDT", seq, price, qty, buy, ts);
    }

    @Test
    void closesAWindowAndEmitsACandleWithCorrectOhlc() {
        List<Frames.CandleFrame> candles = new ArrayList<>();
        MarketState state = new MarketState(1_000, candles::add);

        // Window [0,1000): prices 100, 105, 98, 102 → O=100 H=105 L=98 C=102, vol=4
        state.accept(trade(1, 100, 1, true, 0));
        state.accept(trade(2, 105, 1, false, 200));
        state.accept(trade(3, 98, 1, true, 400));
        state.accept(trade(4, 102, 1, false, 600));
        // A trade in the next window closes the first.
        state.accept(trade(5, 110, 1, true, 1_000));

        assertThat(candles).hasSize(1);
        Frames.CandleFrame c = candles.get(0);
        assertThat(c.open()).isEqualTo(100);
        assertThat(c.high()).isEqualTo(105);
        assertThat(c.low()).isEqualTo(98);
        assertThat(c.close()).isEqualTo(102);
        assertThat(c.volume()).isEqualTo(4);
        assertThat(c.trades()).isEqualTo(4);
    }

    @Test
    void tracksRecentTradesAndLastPrice() {
        MarketState state = new MarketState(1_000, c -> {});
        for (int i = 0; i < 100; i++) {
            state.accept(trade(i, 100 + i, 1, i % 2 == 0, i));
        }
        // Bounded buffer keeps the newest; last price is the most recent print.
        assertThat(state.recentTrades().size()).isLessThanOrEqualTo(64);
        assertThat(state.lastPrice()).isEqualTo(199);
        assertThat(state.recentTrades().get(state.recentTrades().size() - 1).price()).isEqualTo(199);
    }
}
