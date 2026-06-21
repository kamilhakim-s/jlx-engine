package com.lowlatency.tuning;

import org.HdrHistogram.Histogram;

import java.util.concurrent.TimeUnit;

/**
 * The outcome of one {@link LatencyBenchmark} run: the end-to-end latency histogram plus the GC and
 * allocation activity that happened <i>during the measured loop</i>. Keeping the GC/alloc numbers next
 * to the percentiles is the point — a tail spike (high p99.9) lines up with the collections and pause
 * milliseconds recorded over the same window.
 */
public final class BenchmarkResult {

    private final String label;
    private final Histogram latencyNanos;
    private final long gcCollections;
    private final long gcPauseMillis;
    private final long allocatedBytes;
    private final long wallNanos;
    private final long measuredOrders;

    public BenchmarkResult(String label, Histogram latencyNanos, long gcCollections, long gcPauseMillis,
                           long allocatedBytes, long wallNanos, long measuredOrders) {
        this.label = label;
        this.latencyNanos = latencyNanos;
        this.gcCollections = gcCollections;
        this.gcPauseMillis = gcPauseMillis;
        this.allocatedBytes = allocatedBytes;
        this.wallNanos = wallNanos;
        this.measuredOrders = measuredOrders;
    }

    public String label() {
        return label;
    }

    public Histogram latencyNanos() {
        return latencyNanos;
    }

    public long gcCollections() {
        return gcCollections;
    }

    public long gcPauseMillis() {
        return gcPauseMillis;
    }

    public long allocatedBytes() {
        return allocatedBytes;
    }

    public long p(double pct) {
        return latencyNanos.getValueAtPercentile(pct);
    }

    /** Achieved producer rate (orders/sec) over the measured loop's wall time. */
    public double achievedRatePerSec() {
        return wallNanos == 0 ? 0 : measuredOrders / (wallNanos / (double) TimeUnit.SECONDS.toNanos(1));
    }
}
