package com.lowlatency.engine.disruptor;

import com.lowlatency.engine.FastMatchingEngine;
import com.lowlatency.engine.Order;
import com.lmax.disruptor.EventHandler;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.SingleWriterRecorder;

/**
 * The Disruptor consumer: the <b>single writer</b> to the matching engine.
 *
 * <p>This is the core idea of the whole chunk. The engine from Chunks 1–2 is deliberately
 * single-threaded and has no locks. We make it safe under concurrent input not by adding locks, but by
 * guaranteeing that <i>exactly one thread</i> — the Disruptor's consumer thread, which runs this
 * handler — ever touches the engine. Many producers can publish commands concurrently into the ring
 * buffer (a lock-free, wait-free hand-off); the engine itself stays blissfully single-threaded. This
 * is the <b>single-writer principle</b>: the fastest way to share mutable state is to not share it.
 *
 * <p>Because we are on the engine's only thread, we may freely draw orders from the engine's own
 * {@link com.lowlatency.engine.ObjectPool}.
 */
final class MatchingEngineEventHandler implements EventHandler<OrderCommand> {

    private final FastMatchingEngine engine;
    private final Histogram latencyNanos;            // may be null; read only after draining
    private final SingleWriterRecorder latencyRecorder; // may be null; safe to sample live (Chunk 10)

    private volatile long processedCount; // single-writer; volatile for cross-thread reads

    MatchingEngineEventHandler(FastMatchingEngine engine, Histogram latencyNanos) {
        this.engine = engine;
        this.latencyNanos = latencyNanos;
        this.latencyRecorder = null;
    }

    /**
     * Records latency into a {@link SingleWriterRecorder} instead of a plain {@link Histogram}. A plain
     * histogram cannot be read on one thread while written on another; the recorder is built for exactly
     * that — <b>one</b> writer (this consumer, the engine's only thread) plus a separate sampling reader
     * that calls {@code getIntervalHistogram()}. That's the single-writer principle again, now in service
     * of live observability (the Chunk 10 dashboard samples percentiles every ~250 ms).
     */
    MatchingEngineEventHandler(FastMatchingEngine engine, SingleWriterRecorder latencyRecorder) {
        this.engine = engine;
        this.latencyNanos = null;
        this.latencyRecorder = latencyRecorder;
    }

    @Override
    public void onEvent(OrderCommand command, long sequence, boolean endOfBatch) {
        switch (command.type()) {
            case NEW_ORDER -> {
                Order order = engine.orderPool().acquire();
                order.reset(command.orderId(), command.side(), command.orderType(),
                        command.price(), command.quantity());
                engine.submit(order);
            }
            case CANCEL -> engine.cancel(command.orderId());
        }

        processedCount++;

        if (command.ingressNanos() != 0) {
            long elapsed = System.nanoTime() - command.ingressNanos();
            // HdrHistogram requires a positive value; clamp pathological clock readings to 1ns.
            long value = Math.max(1, elapsed);
            if (latencyRecorder != null) {
                latencyRecorder.recordValue(value);
            } else if (latencyNanos != null) {
                latencyNanos.recordValue(value);
            }
        }
    }

    long processedCount() {
        return processedCount;
    }
}
