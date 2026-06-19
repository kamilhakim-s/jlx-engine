package com.lowlatency.bench;

import org.HdrHistogram.Histogram;

import java.util.concurrent.TimeUnit;

/**
 * Coordinated Omission demo — the single most important measurement lesson in low latency.
 *
 * <p>The scenario: a single-threaded server is supposed to handle one request every
 * {@code INTERVAL} (here 1 ms — i.e. a target rate of 1000 req/s). Almost every request is
 * fast (~250 µs), but once in a while the server stalls (GC pause, page fault, lock) for 50 ms.
 *
 * <p>During a 50 ms stall, ~50 requests that were "due" cannot even start — they pile up in a
 * queue. A naive measurement times only the work the server actually did, so it records ONE slow
 * request (50 ms) and silently omits the 50 victims that waited behind it. That is
 * <b>coordinated omission</b>: the measurement loop is coordinated with (paused by) the very stall
 * it is trying to measure, so it under-reports the tail.
 *
 * <p>We compute three views of the same run so you can see the lie and two corrections:
 * <ol>
 *   <li><b>Naive</b> — record only the service time of each request. Looks great. It's wrong.</li>
 *   <li><b>HdrHistogram correction</b> — {@code recordValueWithExpectedInterval} back-fills the
 *       missing samples that <i>should</i> have occurred during a stall, given the expected rate.</li>
 *   <li><b>True latency</b> — model the queue explicitly: a request's latency is measured from when
 *       it was <i>supposed</i> to start (intended time), not when the server got to it. This is what
 *       the user actually experiences.</li>
 * </ol>
 *
 * <p>This is a deterministic arithmetic simulation (no real sleeps) so it runs instantly and gives
 * the same numbers every time — the lesson is in the percentiles, not in wall-clock noise.
 */
public final class CoordinatedOmissionDemo {

    private static final long INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(1);   // target: 1 req/ms
    private static final long FAST_SERVICE_NS = TimeUnit.MICROSECONDS.toNanos(250);
    private static final long STALL_SERVICE_NS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final int  REQUESTS = 100_000;
    private static final int  STALL_EVERY = 10_000;   // a 50 ms stall every 10k requests

    public static void main(String[] args) {
        // Histograms track 1 ns .. 60 s with 3 significant digits of precision.
        long max = TimeUnit.SECONDS.toNanos(60);
        Histogram naive = new Histogram(max, 3);
        Histogram hdrCorrected = new Histogram(max, 3);
        Histogram trueLatency = new Histogram(max, 3);

        long actualEnd = 0; // when the server finished the previous request (single-threaded queue)

        for (int i = 0; i < REQUESTS; i++) {
            long intendedStart = (long) i * INTERVAL_NS;          // when the request was DUE
            boolean stall = (i % STALL_EVERY) == (STALL_EVERY - 1);
            long serviceTime = stall ? STALL_SERVICE_NS : FAST_SERVICE_NS;

            // Single server, FIFO: a request can't start before it's due AND before the
            // previous one finished. If we're behind (a stall pushed us back), we start late.
            long actualStart = Math.max(intendedStart, actualEnd);
            actualEnd = actualStart + serviceTime;

            // (1) Naive: how long the server was busy on THIS request. Misses the queue wait.
            naive.recordValue(serviceTime);

            // (2) HdrHistogram's built-in correction: same input, but it synthesises the samples
            //     that the expected 1 ms cadence implies were stuck waiting during a long service.
            hdrCorrected.recordValueWithExpectedInterval(serviceTime, INTERVAL_NS);

            // (3) Truth: latency the caller felt = finish time minus when it was due to start.
            trueLatency.recordValue(actualEnd - intendedStart);
        }

        System.out.printf("%nSimulated %,d requests at a target of 1 request/ms (1000 req/s).%n", REQUESTS);
        System.out.printf("Fast path = %d µs, stall = %d ms every %,d requests.%n%n",
                TimeUnit.NANOSECONDS.toMicros(FAST_SERVICE_NS),
                TimeUnit.NANOSECONDS.toMillis(STALL_SERVICE_NS),
                STALL_EVERY);

        printTable(naive, hdrCorrected, trueLatency);

        System.out.println();
        System.out.println("Read the p99.9 / p99.99 / max rows: the Naive column says the system is");
        System.out.println("healthy, while True latency shows callers waiting tens of ms. Naive");
        System.out.println("measurement hid the tail by coordinating with the stalls it omitted.");
        System.out.println("Rule for the rest of this project: always measure latency from the");
        System.out.println("intended start, and report percentiles (p99/p99.9), never the average.");
    }

    private static void printTable(Histogram naive, Histogram hdr, Histogram truth) {
        System.out.printf("%-10s %14s %14s %14s%n", "percentile", "Naive (lie)", "Hdr-corrected", "True latency");
        System.out.printf("%-10s %14s %14s %14s%n", "----------", "-----------", "-------------", "------------");
        for (double p : new double[]{50, 90, 99, 99.9, 99.99, 100}) {
            System.out.printf("%-10s %14s %14s %14s%n",
                    label(p),
                    micros(naive.getValueAtPercentile(p)),
                    micros(hdr.getValueAtPercentile(p)),
                    micros(truth.getValueAtPercentile(p)));
        }
        System.out.printf("%-10s %14s %14s %14s%n", "mean",
                micros((long) naive.getMean()),
                micros((long) hdr.getMean()),
                micros((long) truth.getMean()));
    }

    private static String label(double p) {
        return p == 100 ? "max" : "p" + (p == (long) p ? Long.toString((long) p) : Double.toString(p));
    }

    /** Format a nanosecond value as microseconds for readability. */
    private static String micros(long nanos) {
        return String.format("%,d µs", TimeUnit.NANOSECONDS.toMicros(nanos));
    }

    private CoordinatedOmissionDemo() {
    }
}
