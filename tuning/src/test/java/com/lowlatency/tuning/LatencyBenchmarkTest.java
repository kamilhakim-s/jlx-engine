package com.lowlatency.tuning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fast smoke tests for the Chunk 7 tuning harness. They run a tiny benchmark (thousands of orders, not
 * millions) so CI stays quick, and assert the harness wires up end-to-end and the observability plumbing
 * reports sane numbers. The full-scale numbers are produced by {@code ./gradlew :tuning:run} and the
 * {@code bench*} GC tasks — those are for a human to read, not for assertions.
 */
class LatencyBenchmarkTest {

    private static BenchmarkConfig tiny(String waitStrategy) {
        // ring=4096, warmup=2k, measured=5k, 200k/s — finishes in well under a second.
        return new BenchmarkConfig(waitStrategy, waitStrategy, 1 << 12, 2_000, 5_000, 200_000, false);
    }

    @Test
    void runsEndToEndAndRecordsLatency() {
        BenchmarkResult r = new LatencyBenchmark().run(tiny("yielding"));

        // Every measured order matched the deep resting sell and was timed, so percentiles exist and
        // are ordered. We assert structure, not absolute nanoseconds (those vary by machine).
        assertThat(r.latencyNanos().getTotalCount()).isEqualTo(5_000);
        assertThat(r.p(50)).isPositive();
        assertThat(r.p(99)).isGreaterThanOrEqualTo(r.p(50));
        assertThat(r.p(100)).isGreaterThanOrEqualTo(r.p(99));
        assertThat(r.achievedRatePerSec()).isGreaterThan(0);
    }

    @Test
    void hotPathStaysEssentiallyAllocationFree() {
        BenchmarkResult r = new LatencyBenchmark().run(tiny("busyspin"));

        // The matching path allocates nothing per order; over 5,000 measured orders the only heap churn
        // is HdrHistogram/JIT bookkeeping. Assert it's tiny per order rather than zero (HotSpot accounts
        // a little background allocation). < 64 bytes/order is comfortably below per-order object cost.
        if (AllocationCounter.isSupported()) {
            long perOrder = r.allocatedBytes() / 5_000;
            assertThat(perOrder).isLessThan(64);
        }
    }

    @Test
    void gcStatsAreNonNegativeOverTheRun() {
        BenchmarkResult r = new LatencyBenchmark().run(tiny("blocking"));

        assertThat(r.gcCollections()).isGreaterThanOrEqualTo(0);
        assertThat(r.gcPauseMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void affinityHandleNeverThrowsAndDescribesItself() {
        // On macOS/Windows this is a no-op; on Linux it pins. Either way it must not throw and must
        // close cleanly — that graceful degradation is what makes the demo portable.
        try (CpuAffinity affinity = CpuAffinity.pinToAnyCore()) {
            assertThat(affinity.describe()).isNotBlank();
            assertThat(affinity.boundCore()).isGreaterThanOrEqualTo(-1);
        }
    }

    @Test
    void unknownWaitStrategyIsRejected() {
        assertThatThrownBy(() -> WaitStrategies.byName("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
