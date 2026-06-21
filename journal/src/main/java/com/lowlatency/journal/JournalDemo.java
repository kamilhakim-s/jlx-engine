package com.lowlatency.journal;

import com.lowlatency.engine.CollectingTradeHandler;
import com.lowlatency.engine.FastMatchingEngine;
import com.lowlatency.engine.FastOrderBook;
import com.lowlatency.engine.disruptor.DisruptorMatchingService;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.dsl.ProducerType;

import java.nio.file.Path;

/**
 * Demonstrates event-sourced crash recovery. Run:
 * <pre>./gradlew :journal:run</pre>
 *
 * <ol>
 *   <li><b>Live session</b> — feed a deterministic order flow through the engine while a
 *       {@link CommandJournaller} persists every command in parallel; capture the live trade tape.</li>
 *   <li><b>Crash</b> — we simply stop, as if the process died (the journal is on disk).</li>
 *   <li><b>Restart &amp; replay</b> — a brand-new engine replays the journal and we capture its tape.</li>
 *   <li><b>Verify</b> — the replayed tape is byte-for-byte identical to the live one.</li>
 * </ol>
 */
public final class JournalDemo {

    private static final int COMMANDS = 200_000;
    private static final long SEED = 42;

    public static void main(String[] args) {
        Path journalDir = Path.of("data", "journal", "session-" + System.currentTimeMillis());

        // 1) Live: match and journal in parallel; tapeA captures the live trades.
        CommandJournaller journaller = new CommandJournaller(journalDir);
        CollectingTradeHandler tapeA = new CollectingTradeHandler();
        DisruptorMatchingService service = new DisruptorMatchingService(
                1 << 16, ProducerType.SINGLE, new BusySpinWaitStrategy(), 1 << 16,
                (org.HdrHistogram.Histogram) null, journaller, tapeA);
        service.start();
        SyntheticOrderFlow.feed(service, COMMANDS, SEED);
        service.shutdown();   // drains both the matcher and the journaller
        journaller.close();   // flush + close the Chronicle Queue

        System.out.printf("live    : journalled=%,d  engineTrades=%,d  matchedUnits=%,d%n",
                journaller.journalledCount(), service.tradeCount(), service.matchedQuantity());

        // 2+3) "Crash" then restart: replay the journal into a fresh engine; tapeB captures replayed trades.
        CollectingTradeHandler tapeB = new CollectingTradeHandler();
        FastMatchingEngine recovered = new FastMatchingEngine(new FastOrderBook(), tapeB, 1 << 16);
        long applied = new JournalReplayer().replay(journalDir, recovered);

        System.out.printf("recover : applied=%,d  replayedTrades=%,d%n", applied, tapeB.trades().size());

        // 4) Verify determinism.
        boolean identical = tapeA.trades().equals(tapeB.trades());
        System.out.println(identical
                ? "DETERMINISTIC ✓  the replayed trade tape is byte-for-byte identical to the live tape"
                : "MISMATCH ✗  replay diverged from the live run");
        System.out.println("journal dir: " + journalDir + " (under data/, git-ignored)");
    }

    private JournalDemo() {
    }
}
