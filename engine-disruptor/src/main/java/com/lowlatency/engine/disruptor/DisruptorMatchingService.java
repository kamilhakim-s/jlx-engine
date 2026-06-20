package com.lowlatency.engine.disruptor;

import com.lowlatency.engine.FastMatchingEngine;
import com.lowlatency.engine.FastOrderBook;
import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.lmax.disruptor.RingBuffer;
import org.HdrHistogram.Histogram;

/**
 * Front door to the matching engine: a Disruptor ring buffer feeding a single consumer thread that
 * owns a {@link FastMatchingEngine}.
 *
 * <p>Publishers call {@link #publishNewOrder}/{@link #publishCancel}; the consumer thread applies them
 * to the engine in sequence. The two are decoupled by the ring buffer — a fixed-size array of
 * pre-allocated {@link OrderCommand} slots with a pair of monotonically increasing sequence counters
 * (publisher cursor and consumer sequence). Publishing is a {@code next()/get()/publish()} claim-and-
 * commit with no locks; the consumer reads up to the published cursor in batches.
 *
 * <p>Construct with {@link ProducerType#SINGLE} when there's one publisher thread (cheaper); use
 * {@link ProducerType#MULTI} for several. The {@link WaitStrategy} controls how the consumer waits for
 * new events — the central latency/CPU trade-off explored in the demo and docs.
 */
public final class DisruptorMatchingService {

    private final Disruptor<OrderCommand> disruptor;
    private final RingBuffer<OrderCommand> ringBuffer;
    private final MatchingEngineEventHandler handler;
    private final CountingTradeSink tradeSink;

    public DisruptorMatchingService(int ringBufferSize,
                                    ProducerType producerType,
                                    WaitStrategy waitStrategy,
                                    int expectedOrders,
                                    Histogram latencyNanos) {
        this.tradeSink = new CountingTradeSink();
        FastMatchingEngine engine =
                new FastMatchingEngine(new FastOrderBook(), tradeSink, expectedOrders);
        this.handler = new MatchingEngineEventHandler(engine, latencyNanos);

        // Pre-allocated events (OrderCommand::new), a dedicated daemon consumer thread.
        this.disruptor = new Disruptor<>(
                OrderCommand::new, ringBufferSize, DaemonThreadFactory.INSTANCE, producerType, waitStrategy);
        this.disruptor.handleEventsWith(handler);
        this.ringBuffer = disruptor.getRingBuffer();
    }

    /** Convenience constructor: single producer, busy-spin (lowest latency), no latency recording. */
    public DisruptorMatchingService(int ringBufferSize, int expectedOrders) {
        this(ringBufferSize, ProducerType.SINGLE, new BusySpinWaitStrategy(), expectedOrders, null);
    }

    /**
     * Convenience constructor: single producer, busy-spin, recording end-to-end latency into the
     * given histogram. Lets callers (e.g. the market-data demo) avoid importing Disruptor types.
     */
    public DisruptorMatchingService(int ringBufferSize, int expectedOrders, Histogram latencyNanos) {
        this(ringBufferSize, ProducerType.SINGLE, new BusySpinWaitStrategy(), expectedOrders, latencyNanos);
    }

    /** Starts the consumer thread. Call once before publishing. */
    public void start() {
        disruptor.start();
    }

    public void publishNewOrder(long orderId, Side side, OrderType orderType,
                                long price, long quantity, long ingressNanos) {
        long sequence = ringBuffer.next();          // claim the next slot (may spin if full)
        try {
            ringBuffer.get(sequence).setNewOrder(orderId, side, orderType, price, quantity, ingressNanos);
        } finally {
            ringBuffer.publish(sequence);           // commit — now visible to the consumer
        }
    }

    public void publishCancel(long orderId, long ingressNanos) {
        long sequence = ringBuffer.next();
        try {
            ringBuffer.get(sequence).setCancel(orderId, ingressNanos);
        } finally {
            ringBuffer.publish(sequence);
        }
    }

    /** Number of commands the consumer has fully applied (safe to read from any thread). */
    public long processedCount() {
        return handler.processedCount();
    }

    public long tradeCount() {
        return tradeSink.tradeCount();
    }

    public long matchedQuantity() {
        return tradeSink.matchedQuantity();
    }

    /** Drains outstanding events and stops the consumer thread. */
    public void shutdown() {
        disruptor.shutdown();
    }
}
