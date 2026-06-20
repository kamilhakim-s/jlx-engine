package com.lowlatency.streaming;

import com.lowlatency.engine.Side;
import com.lowlatency.engine.TradeRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class TradeEventJsonAndForwarderTest {

    @Test
    void jsonRoundTripPreservesAllFields() {
        TradeEvent original = new TradeEvent("BTCUSDT", 42, 4_212_345L, 1_000_000L, true, 1_700_000_000_000L);
        TradeEvent parsed = TradeEventJson.fromJson(TradeEventJson.toJson(original));
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void forwarderDeliversEngineTradesToTheSinkOffThread() {
        List<TradeEvent> received = new CopyOnWriteArrayList<>();
        Clock fixed = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

        try (AsyncTradeForwarder forwarder =
                     new AsyncTradeForwarder("BTCUSDT", received::add, 1024, fixed)) {
            // Simulate the engine emitting two trades on its consumer thread.
            forwarder.onTrade(new TradeRecord(2, 1, Side.BUY, 100, 5, 0));
            forwarder.onTrade(new TradeRecord(4, 3, Side.SELL, 101, 2, 1));
            await(() -> received.size() == 2);
        }

        assertThat(received).hasSize(2);

        assertThat(received).extracting(TradeEvent::priceTicks).containsExactly(100L, 101L);
        assertThat(received).extracting(TradeEvent::takerBuy).containsExactly(true, false);
        assertThat(received).allSatisfy(e -> {
            assertThat(e.symbol()).isEqualTo("BTCUSDT");
            assertThat(e.timestampMillis()).isEqualTo(1_700_000_000_000L);
        });
    }

    /** Polls until {@code condition} is true or a 5s timeout elapses. */
    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
