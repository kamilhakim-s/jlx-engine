package com.lowlatency.marketdata;

import com.lowlatency.engine.disruptor.DisruptorMatchingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the replay path end to end (no network): each source trade becomes one engine trade. */
class MarketDataReplayerTest {

    @Test
    void eachSourceTradeProducesOneEngineTradeAtRealPriceAndSize() {
        DisruptorMatchingService engine = new DisruptorMatchingService(1 << 12, 1 << 12);
        engine.start();
        MarketDataReplayer replayer = new MarketDataReplayer(engine, false);

        List<AggTrade> source = List.of(
                new AggTrade(1, 4_212_345L, 1_000_000L, 1_700_000_000_000L, false), // taker BUY
                new AggTrade(2, 4_212_300L, 2_500_000L, 1_700_000_000_001L, true),  // taker SELL
                new AggTrade(3, 4_212_400L, 500_000L, 1_700_000_000_002L, false));  // taker BUY

        source.forEach(replayer::accept);
        engine.shutdown(); // drains the ring buffer

        assertThat(replayer.replayedTrades()).isEqualTo(3);
        // 3 trades × 2 commands (maker + taker) each.
        assertThat(engine.processedCount()).isEqualTo(6);
        // Each crossing pair yields exactly one engine trade.
        assertThat(engine.tradeCount()).isEqualTo(3);
        // Matched units equal the sum of source quantities.
        assertThat(engine.matchedQuantity()).isEqualTo(1_000_000L + 2_500_000L + 500_000L);
    }
}
