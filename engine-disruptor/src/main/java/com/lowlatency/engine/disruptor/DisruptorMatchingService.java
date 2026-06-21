package com.lowlatency.engine.disruptor;

import com.lowlatency.engine.FastMatchingEngine;
import com.lowlatency.engine.FastOrderBook;
import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import com.lowlatency.engine.TradeHandler;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.lmax.disruptor.RingBuffer;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.SingleWriterRecorder;

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
        this(ringBufferSize, producerType, waitStrategy, expectedOrders, latencyNanos, null, null);
    }

    /**
     * Full constructor. Two optional hooks support Chunk 5:
     * <ul>
     *   <li>{@code journaller} — an extra {@link EventHandler} that consumes the <i>same</i> ring
     *       buffer <b>in parallel</b> with the matching engine (the LMAX pattern). It sees every
     *       command in the same order the engine does, which is what makes deterministic replay
     *       possible. Journaling latency stays off the matching critical path.</li>
     *   <li>{@code tradeListener} — receives every trade the engine produces (in addition to internal
     *       counting), e.g. to capture the trade tape for a determinism check.</li>
     * </ul>
     */
    public DisruptorMatchingService(int ringBufferSize,
                                    ProducerType producerType,
                                    WaitStrategy waitStrategy,
                                    int expectedOrders,
                                    Histogram latencyNanos,
                                    EventHandler<OrderCommand> journaller,
                                    TradeHandler tradeListener) {
        this.tradeSink = new CountingTradeSink(tradeListener);
        FastMatchingEngine engine =
                new FastMatchingEngine(new FastOrderBook(), tradeSink, expectedOrders);
        this.handler = new MatchingEngineEventHandler(engine, latencyNanos);

        // Pre-allocated events (OrderCommand::new), a dedicated daemon consumer thread.
        this.disruptor = new Disruptor<>(
                OrderCommand::new, ringBufferSize, DaemonThreadFactory.INSTANCE, producerType, waitStrategy);
        if (journaller != null) {
            // Journaller and matcher run as independent parallel consumers of the same events.
            this.disruptor.handleEventsWith(journaller, handler);
        } else {
            this.disruptor.handleEventsWith(handler);
        }
        this.ringBuffer = disruptor.getRingBuffer();
    }

    /**
     * Recorder variant of the full constructor (Chunk 10). Records end-to-end latency into a
     * {@link SingleWriterRecorder} — which, unlike a plain {@link Histogram}, can be safely sampled from
     * another thread while the single consumer keeps writing — so a live dashboard can read percentiles
     * without disturbing the engine. All other wiring is identical.
     */
    public DisruptorMatchingService(int ringBufferSize,
                                    ProducerType producerType,
                                    WaitStrategy waitStrategy,
                                    int expectedOrders,
                                    SingleWriterRecorder latencyRecorder,
                                    EventHandler<OrderCommand> journaller,
                                    TradeHandler tradeListener) {
        this.tradeSink = new CountingTradeSink(tradeListener);
        FastMatchingEngine engine =
                new FastMatchingEngine(new FastOrderBook(), tradeSink, expectedOrders);
        this.handler = new MatchingEngineEventHandler(engine, latencyRecorder);

        this.disruptor = new Disruptor<>(
                OrderCommand::new, ringBufferSize, DaemonThreadFactory.INSTANCE, producerType, waitStrategy);
        if (journaller != null) {
            this.disruptor.handleEventsWith(journaller, handler);
        } else {
            this.disruptor.handleEventsWith(handler);
        }
        this.ringBuffer = disruptor.getRingBuffer();
    }

    /**
     * Convenience for live observability (Chunk 10 dashboard): records latency into a
     * {@link SingleWriterRecorder} and forwards every trade to {@code tradeListener}. Uses a
     * {@link YieldingWaitStrategy} rather than busy-spin so a long-running, often-idle dashboard doesn't
     * peg a core — while still giving near-busy-spin latency under load.
     */
    public DisruptorMatchingService(int ringBufferSize, int expectedOrders,
                                    SingleWriterRecorder latencyRecorder, TradeHandler tradeListener) {
        this(ringBufferSize, ProducerType.SINGLE, new YieldingWaitStrategy(), expectedOrders,
                latencyRecorder, null, tradeListener);
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

    /**
     * Convenience constructor: single producer, busy-spin, forwarding every trade to {@code tradeListener}
     * (e.g. the streaming tier's async forwarder). Keeps Disruptor types out of caller modules.
     */
    public DisruptorMatchingService(int ringBufferSize, int expectedOrders, TradeHandler tradeListener) {
        // (Histogram) null disambiguates from the SingleWriterRecorder full constructor (Chunk 10).
        this(ringBufferSize, ProducerType.SINGLE, new BusySpinWaitStrategy(), expectedOrders,
                (Histogram) null, null, tradeListener);
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
