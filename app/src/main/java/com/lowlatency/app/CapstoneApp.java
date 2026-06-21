package com.lowlatency.app;

import com.lowlatency.engine.disruptor.DisruptorMatchingService;
import com.lowlatency.journal.CommandJournaller;
import com.lowlatency.streaming.AsyncTradeForwarder;
import com.lowlatency.streaming.Candle;
import com.lowlatency.tuning.AllocationCounter;
import com.lowlatency.tuning.GcStats;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.dsl.ProducerType;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.HdrHistogram.Histogram;
import org.agrona.concurrent.BusySpinIdleStrategy;

import com.lowlatency.gateway.OrderEntryClient;
import com.lowlatency.gateway.OrderGateway;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Chunk 9 — the capstone. One runnable program that wires together <b>every prior chunk</b> into the
 * complete low-latency pipeline, with no external infrastructure (no network, no Docker):
 *
 * <pre>
 *   synthetic market data (Ch4 shape) ─▶ Aeron gateway (Ch8) ─▶ matching engine (Ch1-3)
 *                                                                     │
 *                                          ┌──────────────────────────┼──────────────────────────┐
 *                                          ▼                          ▼                          ▼
 *                                  Chronicle journal (Ch5)   end-to-end latency (Ch0/Ch7)   trade seam (Ch6)
 *                                  (parallel consumer)        HdrHistogram + GC/alloc        AsyncTradeForwarder
 *                                                                                                  ▼
 *                                                                                       windowed candles (Ch6 maths)
 * </pre>
 *
 * Run it:
 * <pre>./gradlew :app:run</pre>
 *
 * <p>The engine runs <b>two parallel Disruptor consumers</b>: the matcher and the Chunk 5 journaller,
 * both seeing the identical command sequence — so the run is journalled for deterministic replay while
 * matching. Trades leave the hot path through Chunk 6's {@code AsyncTradeForwarder} seam into an
 * in-process windowed candle aggregator (the same analytics maths that feeds Kafka/Flink in production).
 * Latency is measured end-to-end with the Chunk 0 discipline and the Chunk 7 observability.
 */
public final class CapstoneApp {

    private static final String SYMBOL = "BTCUSDT";
    private static final int STREAM_ID = 7;
    private static final int RING_SIZE = 1 << 16;
    private static final int WARMUP_TRADES = 100_000;
    private static final int MEASURED_TRADES = 500_000;
    private static final long TARGET_TRADE_RATE = 250_000;          // source trades/sec (2 orders each)
    private static final long INTERVAL_NS = TimeUnit.SECONDS.toNanos(1) / TARGET_TRADE_RATE;
    private static final long WINDOW_MS = 500;                       // candle window (Chunk 6 used 10s)

    public static void main(String[] args) throws Exception {
        printHeader();

        Path journalDir = Files.createTempDirectory("capstone-journal");
        MediaDriver mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));

        Histogram latency = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
        WindowedCandleAggregator analytics = new WindowedCandleAggregator(SYMBOL, WINDOW_MS);
        AsyncTradeForwarder forwarder =
                new AsyncTradeForwarder(SYMBOL, analytics, 1 << 16, Clock.systemUTC());
        CommandJournaller journaller = new CommandJournaller(journalDir);

        // The engine: matcher + journaller as parallel consumers of the same ring buffer.
        DisruptorMatchingService engine = new DisruptorMatchingService(
                RING_SIZE, ProducerType.SINGLE, new BusySpinWaitStrategy(),
                RING_SIZE, latency, journaller, forwarder);
        engine.start();

        try (Aeron aeron = Aeron.connect(new Aeron.Context()
                .aeronDirectoryName(mediaDriver.aeronDirectoryName()))) {

            Subscription subscription = aeron.addSubscription("aeron:ipc", STREAM_ID);
            Publication publication = aeron.addPublication("aeron:ipc", STREAM_ID);
            OrderGateway gateway = new OrderGateway(subscription, engine, new BusySpinIdleStrategy());
            gateway.start();

            try (OrderEntryClient client = new OrderEntryClient(publication)) {
                client.awaitConnected();
                AggTradeOverAeron toWire = new AggTradeOverAeron(client);
                SyntheticMarketData source = new SyntheticMarketData(42L, 100_000, 5, 10);

                // Warm up (untimed): let the JIT compile every stage of the pipeline.
                toWire.setMeasureLatency(false);
                source.generate(WARMUP_TRADES, 0, toWire);
                awaitProcessed(engine, journaller, 2L * WARMUP_TRADES);
                latency.reset();

                // Measured: paced, latency stamped from the intended send instant (coordinated-omission safe).
                GcStats gcBefore = GcStats.snapshot();
                long allocBefore = AllocationCounter.totalAllocatedBytes();
                toWire.setMeasureLatency(true);
                long start = System.nanoTime();
                long baseEventTime = System.currentTimeMillis();
                for (int i = 0; i < MEASURED_TRADES; i++) {
                    long intended = start + i * INTERVAL_NS;
                    while (System.nanoTime() < intended) {
                        Thread.onSpinWait();
                    }
                    toWire.accept(source.nextTrade(baseEventTime + i));
                }
                awaitProcessed(engine, journaller, 2L * (WARMUP_TRADES + MEASURED_TRADES));
                long wallNanos = System.nanoTime() - start;
                long allocated = AllocationCounter.bytesSince(allocBefore);
                GcStats gcAfter = GcStats.snapshot();

                gateway.stop();
                forwarder.close();     // drain the seam into the analytics tier
                analytics.flush();     // close the final window

                printReport(latency, engine, journaller, analytics, forwarder, client,
                        gcAfter.since(gcBefore), allocated, wallNanos);
            }
            gateway.stop();
        } finally {
            engine.shutdown();
            journaller.close();
            mediaDriver.close();
            deleteRecursively(journalDir);
        }
    }

    private static void awaitProcessed(DisruptorMatchingService engine, CommandJournaller journaller, long expected) {
        // Both parallel consumers must catch up: the matcher (processedCount) and the journaller.
        while (engine.processedCount() < expected || journaller.journalledCount() < expected) {
            LockSupport.parkNanos(1_000);
        }
    }

    private static void printHeader() {
        System.out.printf("Capstone — full low-latency pipeline (market data → Aeron → engine → journal + analytics)%n");
        System.out.printf("symbol=%s  warmup=%,d trades  measured=%,d trades  target=%,d trades/s  window=%dms%n%n",
                SYMBOL, WARMUP_TRADES, MEASURED_TRADES, TARGET_TRADE_RATE, WINDOW_MS);
    }

    private static void printReport(Histogram latency, DisruptorMatchingService engine,
                                    CommandJournaller journaller, WindowedCandleAggregator analytics,
                                    AsyncTradeForwarder forwarder, OrderEntryClient client,
                                    String gcSummary, long allocated, long wallNanos) {
        double rate = MEASURED_TRADES / (wallNanos / (double) TimeUnit.SECONDS.toNanos(1));
        System.out.println("Pipeline result");
        System.out.printf("  engine:    trades=%,d  matched qty=%,d  (achieved %,.0f trades/s)%n",
                engine.tradeCount(), engine.matchedQuantity(), rate);
        System.out.printf("  journal:   %,d commands persisted (Chronicle Queue → deterministic replay)%n",
                journaller.journalledCount());
        System.out.printf("  analytics: %,d candles  total volume=%,d  (forwarder drops=%,d)%n",
                analytics.candles().size(), analytics.totalVolume(), forwarder.droppedCount());
        System.out.printf("  transport: publisher backpressure retries=%,d%n", client.backpressureCount());
        System.out.printf("  latency (client → Aeron → gateway → match):%n");
        System.out.printf("    p50=%s  p99=%s  p99.9=%s  p99.99=%s  max=%s%n",
                us(latency.getValueAtPercentile(50)), us(latency.getValueAtPercentile(99)),
                us(latency.getValueAtPercentile(99.9)), us(latency.getValueAtPercentile(99.99)),
                us(latency.getMaxValue()));
        System.out.printf("  gc/alloc over measured window: %s, %s allocated%n", gcSummary, bytes(allocated));

        List<Candle> candles = analytics.candles();
        int show = Math.min(3, candles.size());
        if (show > 0) {
            System.out.println("  first candles:");
            for (int i = 0; i < show; i++) {
                System.out.printf("    %s%n", candles.get(i));
            }
        }
        boolean consistent = analytics.totalVolume() == engine.matchedQuantity() && forwarder.droppedCount() == 0;
        System.out.printf("%n  cross-tier check: analytics volume %s engine matched quantity%n",
                consistent ? "==" : "!= (drops occurred)");
    }

    private static String us(long nanos) {
        return String.format("%.2f µs", nanos / 1_000.0);
    }

    private static String bytes(long b) {
        if (b < 1024) {
            return b + " B";
        }
        if (b < 1024 * 1024) {
            return String.format("%.1f KB", b / 1024.0);
        }
        return String.format("%.1f MB", b / (1024.0 * 1024.0));
    }

    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort cleanup of the temp journal
                }
            });
        } catch (Exception ignored) {
            // temp dir cleanup is best-effort
        }
    }

    private CapstoneApp() {
    }
}
