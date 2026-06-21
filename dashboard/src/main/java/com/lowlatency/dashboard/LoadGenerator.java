package com.lowlatency.dashboard;

import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import com.lowlatency.engine.disruptor.DisruptorMatchingService;

import java.util.Random;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * A toggleable synthetic load source. Live Binance trades arrive only a few-to-hundreds per second, so the
 * engine's latency <i>tail</i> under that feed is idle/scheduling-dominated and undersells it. When the
 * user flips <b>stress</b> on, this drives a high, paced stream of synthetic orders into the same engine so
 * the latency / throughput / GC panels show the engine genuinely under load.
 *
 * <p>Each "trade" is published as a crossing maker+taker pair (the Chunk 4 reconstruction), and the taker
 * carries an ingress timestamp so the engine records end-to-end latency for it. The synthetic price walks
 * around the <b>live</b> price (via {@code basePriceSupplier}) so the candle chart stays coherent rather
 * than jumping between price regimes when stress is toggled.
 *
 * <p>Runs on its own thread; when disabled it parks (no CPU). It publishes from a different thread than the
 * live feed, which is why the engine is constructed with {@code ProducerType.MULTI}.
 */
final class LoadGenerator {

    private final DisruptorMatchingService engine;
    private final LongSupplier basePriceSupplier;
    private final Random random = new Random(1234);
    private final Thread thread;

    private volatile boolean enabled;
    private volatile long ratePerSec = 200_000;
    private volatile boolean running = true;
    private long nextOrderId = 1_000_000_000L; // disjoint id space from the live feed
    private long price;

    LoadGenerator(DisruptorMatchingService engine, LongSupplier basePriceSupplier) {
        this.engine = engine;
        this.basePriceSupplier = basePriceSupplier;
        this.thread = new Thread(this::loop, "load-generator");
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    void stop() {
        running = false;
        LockSupport.unpark(thread);
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        LockSupport.unpark(thread);
    }

    void setRatePerSec(long ratePerSec) {
        this.ratePerSec = Math.max(1, ratePerSec);
    }

    boolean isEnabled() {
        return enabled;
    }

    long ratePerSec() {
        return ratePerSec;
    }

    private void loop() {
        while (running) {
            if (!enabled) {
                LockSupport.parkNanos(50_000_000L); // 50 ms idle; re-checks the flag
                continue;
            }
            long intervalNanos = 1_000_000_000L / ratePerSec;
            long next = System.nanoTime();
            // Burst in a paced inner loop while enabled; re-read the flag often so toggling is responsive.
            while (running && enabled) {
                next += intervalNanos;
                while (System.nanoTime() < next) {
                    Thread.onSpinWait();
                }
                publishOne();
            }
        }
    }

    private void publishOne() {
        long base = basePriceSupplier.getAsLong();
        if (base <= 0) {
            base = 100_000;
        }
        // Small random walk around the live price; clamp positive.
        price = Math.max(1, (price == 0 ? base : price) + random.nextLong(-5, 6));
        // Re-anchor occasionally so we track the live price as it moves.
        if (random.nextInt(256) == 0) {
            price = base;
        }
        long qty = 1 + random.nextLong(10);
        boolean takerBuy = random.nextBoolean();
        Side takerSide = takerBuy ? Side.BUY : Side.SELL;
        Side makerSide = takerSide.opposite();

        // Maker rests (untimed); taker crosses it and produces the trade (timed for latency).
        engine.publishNewOrder(nextOrderId++, makerSide, OrderType.LIMIT, price, qty, 0);
        engine.publishNewOrder(nextOrderId++, takerSide, OrderType.LIMIT, price, qty, System.nanoTime());
    }
}
