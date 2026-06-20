package com.lowlatency.marketdata;

import com.lowlatency.engine.disruptor.DisruptorMatchingService;
import org.HdrHistogram.Histogram;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * End-to-end Chunk 4 demo: pull real Binance data and run it through the matching engine.
 *
 * <pre>
 *   ./gradlew :engine-disruptor &amp;&amp; ./gradlew :market-data:run                 # historical replay
 *   ./gradlew :market-data:run --args="live"                                # 20s of live stream
 * </pre>
 *
 * <p>The historical run downloads a day of BTCUSDT aggregate trades (cached under {@code data/}),
 * streams them through {@link MarketDataReplayer} into the engine as fast as possible, and reports
 * throughput plus end-to-end latency percentiles. The live run connects to the public WebSocket and
 * replays trades as they arrive. Both require network access; the demo prints guidance if offline.
 */
public final class MarketDataDemo {

    private static final String SYMBOL = "BTCUSDT";
    private static final SymbolScale SCALE = SymbolScale.BTCUSDT;
    // A fixed past date that is reliably published. Change it to any available day.
    private static final LocalDate HISTORICAL_DAY = LocalDate.of(2024, 6, 3);
    private static final long MAX_TRADES = 2_000_000;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("live")) {
            runLive();
        } else {
            runHistorical();
        }
    }

    private static void runHistorical() throws Exception {
        System.out.printf("Historical replay: %s aggTrades for %s (max %,d)%n%n",
                SYMBOL, HISTORICAL_DAY, MAX_TRADES);

        Histogram latency = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
        DisruptorMatchingService engine = new DisruptorMatchingService(1 << 16, 1 << 16, latency);
        engine.start();
        MarketDataReplayer replayer = new MarketDataReplayer(engine, true);

        BinanceHistoricalDownloader downloader = new BinanceHistoricalDownloader();
        long start = System.nanoTime();
        long sourceTrades;
        try {
            System.out.println("Downloading (first run) / reading cache ...");
            sourceTrades = downloader.streamDay(SYMBOL, HISTORICAL_DAY, SCALE, MAX_TRADES, replayer);
        } catch (java.io.IOException offline) {
            System.out.println("Could not fetch data: " + offline.getMessage());
            System.out.println("Check your connection, or set HISTORICAL_DAY to another date.");
            engine.shutdown();
            return;
        }

        // Each source trade published 2 commands; wait until the engine has applied them all.
        awaitProcessed(engine, sourceTrades * 2);
        double seconds = (System.nanoTime() - start) / 1e9;
        engine.shutdown();

        System.out.printf("%nReplayed %,d source trades in %.2fs (%,d trades/s through the engine)%n",
                sourceTrades, seconds, (long) (sourceTrades / Math.max(seconds, 1e-9)));
        System.out.printf("engine trades=%,d  matched units=%,d%n",
                engine.tradeCount(), engine.matchedQuantity());
        System.out.printf("end-to-end latency  p50=%s p99=%s p99.9=%s max=%s%n",
                us(latency.getValueAtPercentile(50)), us(latency.getValueAtPercentile(99)),
                us(latency.getValueAtPercentile(99.9)), us(latency.getMaxValue()));
    }

    private static void runLive() throws Exception {
        System.out.printf("Live stream: %s@aggTrade for 20s%n%n", SYMBOL);

        DisruptorMatchingService engine = new DisruptorMatchingService(1 << 16, 1 << 16);
        engine.start();
        MarketDataReplayer replayer = new MarketDataReplayer(engine, false);

        try {
            new BinanceLiveClient().streamFor(SYMBOL, SCALE, Duration.ofSeconds(20), trade -> {
                replayer.accept(trade);
                if (replayer.replayedTrades() % 50 == 0) {
                    System.out.printf("  ... %,d live trades, last price=%d%n",
                            replayer.replayedTrades(), trade.priceTicks());
                }
            });
        } catch (RuntimeException offline) {
            System.out.println("Live stream unavailable: " + offline.getMessage());
            engine.shutdown();
            return;
        }

        awaitProcessed(engine, replayer.replayedTrades() * 2);
        engine.shutdown();
        System.out.printf("%nLive: replayed %,d trades, engine produced %,d trades (%,d units)%n",
                replayer.replayedTrades(), engine.tradeCount(), engine.matchedQuantity());
    }

    private static void awaitProcessed(DisruptorMatchingService engine, long expected) {
        while (engine.processedCount() < expected) {
            LockSupport.parkNanos(50_000);
        }
    }

    private static String us(long nanos) {
        return String.format("%.2f µs", nanos / 1_000.0);
    }

    private MarketDataDemo() {
    }
}
