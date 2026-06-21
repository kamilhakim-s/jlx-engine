package com.lowlatency.streaming;

import com.lowlatency.engine.Side;
import com.lowlatency.engine.Trade;
import com.lowlatency.engine.TradeHandler;

import java.io.Closeable;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The boundary OUT of the low-latency core. The engine calls {@link #onTrade} on its single consumer
 * thread; this class does the minimum there — snapshot the trade into a {@link TradeEvent} and hand it
 * to a bounded queue — then a <b>separate forwarder thread</b> drains the queue to the real sink
 * (Kafka). Network I/O and serialisation never run on the engine thread.
 *
 * <p>This is the deliberate architectural seam: the engine is latency-critical and must not block on a
 * broker; everything past this queue optimises for throughput and durability, not nanoseconds. If the
 * sink can't keep up the bounded queue fills and we <b>drop</b> (counting drops) rather than stall the
 * engine — one backpressure policy; blocking would be another. The small per-trade {@link TradeEvent}
 * allocation is the acknowledged price of leaving the zero-GC core.
 */
public final class AsyncTradeForwarder implements TradeHandler, Closeable {

    private final String symbol;
    private final Consumer<TradeEvent> sink;
    private final Clock clock;
    private final ArrayBlockingQueue<TradeEvent> queue;
    private final Thread forwarder;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;

    public AsyncTradeForwarder(String symbol, Consumer<TradeEvent> sink, int capacity, Clock clock) {
        this.symbol = symbol;
        this.sink = sink;
        this.clock = clock;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.forwarder = new Thread(this::drain, "trade-forwarder");
        this.forwarder.setDaemon(true);
        this.forwarder.start();
    }

    @Override
    public void onTrade(Trade trade) {
        TradeEvent event = new TradeEvent(
                symbol, trade.sequence(), trade.price(), trade.quantity(),
                trade.takerSide() == Side.BUY, clock.millis());
        if (!queue.offer(event)) {
            dropped.incrementAndGet(); // sink can't keep up — drop rather than stall the engine
        }
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            try {
                TradeEvent event = queue.poll(50, TimeUnit.MILLISECONDS);
                if (event != null) {
                    sink.accept(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public long droppedCount() {
        return dropped.get();
    }

    @Override
    public void close() {
        running = false;
        try {
            forwarder.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
