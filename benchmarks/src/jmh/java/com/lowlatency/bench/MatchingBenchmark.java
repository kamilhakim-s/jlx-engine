package com.lowlatency.bench;

import com.lowlatency.engine.FastMatchingEngine;
import com.lowlatency.engine.FastOrderBook;
import com.lowlatency.engine.MatchingEngine;
import com.lowlatency.engine.Order;
import com.lowlatency.engine.OrderBook;
import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import com.lowlatency.engine.Trade;
import com.lowlatency.engine.TradeHandler;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Chunk 2's before/after benchmark: Chunk 1 {@link MatchingEngine} vs Chunk 2
 * {@link FastMatchingEngine}. Run with the GC profiler (configured in build.gradle.kts):
 *
 * <pre>./gradlew :benchmarks:jmh -Pjmh.includes=MatchingBenchmark</pre>
 *
 * Read <b>Score</b> (ns/op) and <b>·gc.alloc.rate.norm</b> (bytes/op). It exercises two scenarios on
 * purpose, because they teach opposite halves of one lesson:
 *
 * <ol>
 *   <li><b>matchOnly</b> — a deep resting order is filled one unit at a time. Nothing escapes the
 *       inlined call, so the JVM's <i>escape analysis</i> deletes even the Chunk 1 engine's
 *       per-trade {@code TradeRecord}: both engines measure ~0 B/op and the simpler one is even
 *       faster. Naive code can be allocation-free <i>when EA succeeds</i> — but that's fragile.</li>
 *   <li><b>restAndSweep</b> — each op rests {@code RESTING} orders across price levels (they escape
 *       into the book's long-lived collections, so EA <i>cannot</i> remove them) and then sweeps
 *       them. Now Chunk 1 allocates orders + {@code TreeMap} nodes + boxed {@link Long} keys +
 *       {@code ArrayDeque} nodes + {@code TradeRecord}s; Chunk 2 draws all of it from pools and reuses
 *       a trade flyweight. This is where the allocation gap is real and EA-proof.</li>
 * </ol>
 *
 * <p>Note what the latency columns do <i>not</i> show: in a warm, single-threaded microbenchmark the
 * fewer allocations of Chunk 2 do not buy lower average ns/op — young-gen (TLAB) allocation is nearly
 * free and no GC pressure builds. The payoff of zero-allocation is <b>fewer/shorter GC pauses</b>,
 * which shows up as better <i>tail</i> latency and sustained throughput under real load (the Chunk 0
 * lesson), and as determinism once many orders are live concurrently (Chunk 3+).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MatchingBenchmark {

    private static final long PRICE = 100_000;             // outside the Long cache [-128,127]
    private static final long DEEP = Long.MAX_VALUE / 4;   // effectively inexhaustible resting size

    private static final int LEVELS = 8;
    private static final int PER_LEVEL = 2;
    private static final int RESTING = LEVELS * PER_LEVEL;     // orders rested per churn op
    private static final int OPS_PER_INVOCATION = RESTING + 1; // + the sweeping order

    /** Counts matched quantity; reads the (possibly reused) trade flyweight without allocating. */
    private static final class CountingHandler implements TradeHandler {
        long matched;
        @Override
        public void onTrade(Trade t) {
            matched += t.quantity();
        }
    }

    // Scenario 1: match against deep resting liquidity (the hot path).
    private MatchingEngine simpleMatch;
    private Order simpleMatchIncoming;
    private FastMatchingEngine fastMatch;

    // Scenario 2: build-and-drain the book (objects escape into the book).
    private MatchingEngine simpleChurn;
    private FastMatchingEngine fastChurn;

    private long id = 1_000_000;

    @Setup(Level.Trial)
    public void setup() {
        simpleMatch = new MatchingEngine(new OrderBook(), new CountingHandler());
        simpleMatch.submit(new Order(1, Side.SELL, OrderType.LIMIT, PRICE, DEEP));
        simpleMatchIncoming = new Order();

        fastMatch = new FastMatchingEngine(new FastOrderBook(), new CountingHandler(), 1024);
        Order seed = fastMatch.orderPool().acquire();
        seed.reset(1, Side.SELL, OrderType.LIMIT, PRICE, DEEP);
        fastMatch.submit(seed);

        simpleChurn = new MatchingEngine(new OrderBook(), new CountingHandler());
        fastChurn = new FastMatchingEngine(new FastOrderBook(), new CountingHandler(), 1024);
    }

    @Benchmark
    public void chunk1_matchOnly() {
        simpleMatchIncoming.reset(id++, Side.BUY, OrderType.LIMIT, PRICE, 1);
        simpleMatch.submit(simpleMatchIncoming);
    }

    @Benchmark
    public void chunk2_matchOnly() {
        Order o = fastMatch.orderPool().acquire();
        o.reset(id++, Side.BUY, OrderType.LIMIT, PRICE, 1);
        fastMatch.submit(o); // fully fills → recycled to the pool
    }

    @Benchmark
    @OperationsPerInvocation(OPS_PER_INVOCATION)
    public void chunk1_restAndSweep() {
        for (int k = 0; k < RESTING; k++) {
            simpleChurn.submit(new Order(id++, Side.BUY, OrderType.LIMIT, BASE(k), 1));
        }
        simpleChurn.submit(new Order(id++, Side.SELL, OrderType.LIMIT, PRICE - (LEVELS - 1), RESTING));
    }

    @Benchmark
    @OperationsPerInvocation(OPS_PER_INVOCATION)
    public void chunk2_restAndSweep() {
        for (int k = 0; k < RESTING; k++) {
            Order o = fastChurn.orderPool().acquire();
            o.reset(id++, Side.BUY, OrderType.LIMIT, BASE(k), 1);
            fastChurn.submit(o);
        }
        Order sweep = fastChurn.orderPool().acquire();
        sweep.reset(id++, Side.SELL, OrderType.LIMIT, PRICE - (LEVELS - 1), RESTING);
        fastChurn.submit(sweep);
    }

    /** Spreads resting bids across LEVELS distinct prices just below PRICE. */
    private static long BASE(int k) {
        return PRICE - (k % LEVELS);
    }
}
