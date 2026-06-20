package com.lowlatency.app;

import com.lowlatency.engine.FastMatchingEngine;
import com.lowlatency.engine.FastOrderBook;
import com.lowlatency.engine.disruptor.DisruptorMatchingService;
import com.lowlatency.journal.CommandJournaller;
import com.lowlatency.journal.JournalReplayer;
import com.lowlatency.streaming.AsyncTradeForwarder;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.dsl.ProducerType;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.HdrHistogram.Histogram;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lowlatency.gateway.OrderEntryClient;
import com.lowlatency.gateway.OrderGateway;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the whole capstone pipeline on an embedded Aeron driver (no network, no Docker):
 * synthetic market data → Aeron gateway → engine (+ parallel Chronicle journal) → trade seam → windowed
 * candles. Asserts the tiers agree, and — the capstone payoff — that replaying the journal rebuilds the
 * exact same matched quantity, proving the run is deterministic and recoverable (Chunk 5) across the
 * whole assembled system.
 */
class CapstonePipelineTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final int STREAM_ID = 2002;
    private static final int TRADES = 2_000;

    @Test
    void fullPipelineMatchesJournalsAndAggregates(@TempDir Path journalDir) {
        MediaDriver mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));

        Histogram latency = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
        WindowedCandleAggregator analytics = new WindowedCandleAggregator(SYMBOL, 500);
        AsyncTradeForwarder forwarder =
                new AsyncTradeForwarder(SYMBOL, analytics, 1 << 16, Clock.systemUTC());
        CommandJournaller journaller = new CommandJournaller(journalDir);
        DisruptorMatchingService engine = new DisruptorMatchingService(
                1 << 16, ProducerType.SINGLE, new BusySpinWaitStrategy(), 1 << 16, latency, journaller, forwarder);
        engine.start();

        long liveMatchedQty;
        try (Aeron aeron = Aeron.connect(new Aeron.Context()
                .aeronDirectoryName(mediaDriver.aeronDirectoryName()))) {

            Subscription subscription = aeron.addSubscription("aeron:ipc", STREAM_ID);
            Publication publication = aeron.addPublication("aeron:ipc", STREAM_ID);
            OrderGateway gateway = new OrderGateway(subscription, engine,
                    new BackoffIdleStrategy(100, 10, 1_000, 100_000));
            gateway.start();

            try (OrderEntryClient client = new OrderEntryClient(publication)) {
                client.awaitConnected();
                AggTradeOverAeron toWire = new AggTradeOverAeron(client);
                SyntheticMarketData source = new SyntheticMarketData(42L, 100_000, 5, 10);
                source.generate(TRADES, 0, toWire);

                awaitProcessed(engine, journaller, 2L * TRADES);
                gateway.stop();
            }

            forwarder.close();
            analytics.flush();
            liveMatchedQty = engine.matchedQuantity();

            // Every source trade produced exactly one engine trade; both other consumers saw everything.
            assertThat(engine.tradeCount()).isEqualTo(TRADES);
            assertThat(journaller.journalledCount()).isEqualTo(2L * TRADES); // maker + taker per trade
            assertThat(forwarder.droppedCount()).isZero();
            // The analytics tier saw the same volume the engine matched, spread over ≥1 window.
            assertThat(analytics.totalVolume()).isEqualTo(liveMatchedQty);
            assertThat(analytics.candles()).isNotEmpty();
        } finally {
            engine.shutdown();
            journaller.close();
            mediaDriver.close();
        }

        // Capstone payoff: deterministic replay. Rebuild from the journal alone into a fresh engine and
        // confirm the same matched quantity — the run is reproducible and recoverable end to end.
        long[] replayedQty = {0};
        FastMatchingEngine rebuilt = new FastMatchingEngine(
                new FastOrderBook(), trade -> replayedQty[0] += trade.quantity(), 1 << 16);
        long replayedCommands = new JournalReplayer().replay(journalDir, rebuilt);

        assertThat(replayedCommands).isEqualTo(2L * TRADES);
        assertThat(replayedQty[0]).isEqualTo(liveMatchedQty);
    }

    private static void awaitProcessed(DisruptorMatchingService engine, CommandJournaller journaller, long expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (engine.processedCount() < expected || journaller.journalledCount() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out: processed " + engine.processedCount()
                        + " journalled " + journaller.journalledCount() + " of " + expected);
            }
            LockSupport.parkNanos(50_000);
        }
    }
}
