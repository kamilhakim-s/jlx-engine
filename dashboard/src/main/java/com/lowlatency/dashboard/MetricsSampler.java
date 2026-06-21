package com.lowlatency.dashboard;

import com.lowlatency.engine.disruptor.DisruptorMatchingService;
import com.lowlatency.tuning.AllocationCounter;
import com.lowlatency.tuning.GcStats;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.SingleWriterRecorder;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically (every {@code intervalMillis}) samples the engine's live state and broadcasts it as
 * {@code metrics} and {@code tape} frames. This is the low-latency hero of the dashboard: latency
 * percentiles, throughput, and GC/allocation, all read <b>without disturbing the engine</b>.
 *
 * <p>The latency percentiles come from the engine's {@link SingleWriterRecorder} via
 * {@code getIntervalHistogram()} — a phased read that is safe to run on this thread while the single engine
 * consumer keeps recording. Throughput is the per-interval delta of the engine's {@code processedCount()}
 * and {@code tradeCount()}; GC and allocation are deltas of {@link GcStats}/{@link AllocationCounter}
 * (Chunk 7). Percentiles are per-interval, so the panel reflects "latency right now", not a lifetime
 * average.
 */
final class MetricsSampler {

    private final DisruptorMatchingService engine;
    private final SingleWriterRecorder latencyRecorder;
    private final MarketState marketState;
    private final SseHub hub;
    private final long intervalMillis;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "metrics-sampler");
                t.setDaemon(true);
                return t;
            });

    private Histogram intervalHistogram;     // reused across samples (off the hot path)
    private GcStats prevGc;
    private long prevAlloc;
    private long prevProcessed;
    private long prevTrades;
    private long prevNanos;

    MetricsSampler(DisruptorMatchingService engine, SingleWriterRecorder latencyRecorder,
                   MarketState marketState, SseHub hub, long intervalMillis) {
        this.engine = engine;
        this.latencyRecorder = latencyRecorder;
        this.marketState = marketState;
        this.hub = hub;
        this.intervalMillis = intervalMillis;
    }

    void start() {
        prevGc = GcStats.snapshot();
        prevAlloc = AllocationCounter.totalAllocatedBytes();
        prevProcessed = engine.processedCount();
        prevTrades = engine.tradeCount();
        prevNanos = System.nanoTime();
        scheduler.scheduleAtFixedRate(this::sampleSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    void stop() {
        scheduler.shutdownNow();
    }

    private void sampleSafely() {
        try {
            sample();
        } catch (Exception e) {
            // A sampling hiccup must never kill the scheduler; skip this tick.
        }
    }

    private void sample() {
        long now = System.nanoTime();
        double seconds = Math.max(1e-9, (now - prevNanos) / 1_000_000_000.0);
        prevNanos = now;

        intervalHistogram = latencyRecorder.getIntervalHistogram(intervalHistogram);

        long processed = engine.processedCount();
        long trades = engine.tradeCount();
        double ordersPerSec = (processed - prevProcessed) / seconds;
        double tradesPerSec = (trades - prevTrades) / seconds;
        prevProcessed = processed;
        prevTrades = trades;

        GcStats gc = GcStats.snapshot();
        long gcCollections = gc.collectionsSince(prevGc);
        long gcPause = gc.pauseMillisSince(prevGc);
        prevGc = gc;

        long alloc = AllocationCounter.totalAllocatedBytes();
        long allocBytes = Math.max(0, alloc - prevAlloc);
        prevAlloc = alloc;

        Histogram h = intervalHistogram;
        Frames.MetricsFrame metrics = new Frames.MetricsFrame(
                System.currentTimeMillis(),
                h.getValueAtPercentile(50), h.getValueAtPercentile(99), h.getValueAtPercentile(99.9),
                h.getValueAtPercentile(99.99), h.getMaxValue(), h.getTotalCount(),
                ordersPerSec, tradesPerSec, processed, trades,
                gcCollections, gcPause, allocBytes, allocBytes / seconds);
        hub.broadcast("metrics", metrics);
        hub.broadcast("tape", new Frames.TapeFrame(metrics.ts(), marketState.recentTrades()));
    }
}
