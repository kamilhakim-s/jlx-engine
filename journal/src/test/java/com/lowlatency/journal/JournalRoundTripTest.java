package com.lowlatency.journal;

import com.lowlatency.engine.CollectingTradeHandler;
import com.lowlatency.engine.FastMatchingEngine;
import com.lowlatency.engine.FastOrderBook;
import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import com.lowlatency.engine.disruptor.CommandType;
import com.lowlatency.engine.disruptor.DisruptorMatchingService;
import com.lowlatency.engine.disruptor.OrderCommand;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.ProducerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JournalRoundTripTest {

    @Test
    void replayReproducesByteForByteIdenticalTradeTape(@TempDir Path tmp) {
        Path journalDir = tmp.resolve("journal");

        // Live: journal in parallel with matching, capture the live trade tape.
        CommandJournaller journaller = new CommandJournaller(journalDir);
        CollectingTradeHandler liveTape = new CollectingTradeHandler();
        DisruptorMatchingService service = new DisruptorMatchingService(
                1 << 14, ProducerType.SINGLE, new YieldingWaitStrategy(), 1 << 14, null, journaller, liveTape);
        service.start();
        SyntheticOrderFlow.feed(service, 20_000, 7);
        service.shutdown();
        journaller.close();

        // Crash + restart: replay into a fresh engine, capture the replayed tape.
        CollectingTradeHandler replayTape = new CollectingTradeHandler();
        FastMatchingEngine recovered = new FastMatchingEngine(new FastOrderBook(), replayTape, 1 << 14);
        long applied = new JournalReplayer().replay(journalDir, recovered);

        assertThat(journaller.journalledCount()).isEqualTo(20_000);
        assertThat(applied).isEqualTo(20_000);
        assertThat(liveTape.trades()).isNotEmpty();              // the workload actually traded
        assertThat(replayTape.trades()).isEqualTo(liveTape.trades()); // identical tape ⇒ identical state
    }

    @Test
    void codecJournalsAndReplaysWithoutTheDisruptor(@TempDir Path tmp) {
        Path journalDir = tmp.resolve("journal");

        CommandJournaller journaller = new CommandJournaller(journalDir);
        journaller.onEvent(newOrder(1, Side.SELL, OrderType.LIMIT, 100, 5), 0, false);
        journaller.onEvent(newOrder(2, Side.BUY, OrderType.LIMIT, 100, 5), 1, true);
        assertThat(journaller.journalledCount()).isEqualTo(2);
        journaller.close();

        CollectingTradeHandler tape = new CollectingTradeHandler();
        FastMatchingEngine engine = new FastMatchingEngine(new FastOrderBook(), tape, 64);
        long applied = new JournalReplayer().replay(journalDir, engine);

        assertThat(applied).isEqualTo(2);
        assertThat(tape.trades()).singleElement().satisfies(t -> {
            assertThat(t.price()).isEqualTo(100L);
            assertThat(t.quantity()).isEqualTo(5L);
            assertThat(t.takerSide()).isEqualTo(Side.BUY);
        });
    }

    @Test
    void cancelCommandsSurviveTheRoundTrip(@TempDir Path tmp) {
        Path journalDir = tmp.resolve("journal");

        CommandJournaller journaller = new CommandJournaller(journalDir);
        journaller.onEvent(newOrder(1, Side.SELL, OrderType.LIMIT, 100, 5), 0, false);
        journaller.onEvent(cancel(1), 1, false);                              // cancels the resting sell
        journaller.onEvent(newOrder(2, Side.BUY, OrderType.LIMIT, 100, 5), 2, true); // now rests, no trade
        journaller.close();

        CollectingTradeHandler tape = new CollectingTradeHandler();
        FastMatchingEngine engine = new FastMatchingEngine(new FastOrderBook(), tape, 64);
        new JournalReplayer().replay(journalDir, engine);

        assertThat(tape.trades()).isEmpty();             // the maker was cancelled before the buy arrived
        assertThat(engine.book().bestBid()).isEqualTo(100L);
    }

    private static OrderCommand newOrder(long id, Side side, OrderType type, long price, long qty) {
        OrderCommand c = new OrderCommand();
        c.setNewOrder(id, side, type, price, qty, 0);
        return c;
    }

    private static OrderCommand cancel(long id) {
        OrderCommand c = new OrderCommand();
        c.setCancel(id, 0);
        assertThat(c.type()).isEqualTo(CommandType.CANCEL);
        return c;
    }
}
