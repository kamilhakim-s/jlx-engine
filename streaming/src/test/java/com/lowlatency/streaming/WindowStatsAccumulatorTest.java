package com.lowlatency.streaming;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WindowStatsAccumulatorTest {

    @Test
    void computesOhlcVolumeVwapAndImbalance() {
        WindowStatsAccumulator acc = new WindowStatsAccumulator();
        acc.add(100, 2, true);    // buy 2 @100
        acc.add(102, 3, false);   // sell 3 @102 (high)
        acc.add(98, 1, true);     // buy 1 @98 (low)
        acc.add(101, 4, true);    // buy 4 @101 (close)

        Candle c = acc.toCandle("BTCUSDT", 0, 10_000);

        assertThat(c.open()).isEqualTo(100);
        assertThat(c.high()).isEqualTo(102);
        assertThat(c.low()).isEqualTo(98);
        assertThat(c.close()).isEqualTo(101);
        assertThat(c.volume()).isEqualTo(10);              // 2+3+1+4
        assertThat(c.trades()).isEqualTo(4);
        assertThat(c.buyVolume()).isEqualTo(7);            // 2+1+4
        assertThat(c.sellVolume()).isEqualTo(3);

        // VWAP = (100*2 + 102*3 + 98*1 + 101*4) / 10 = (200+306+98+404)/10 = 1008/10
        assertThat(c.vwap()).isCloseTo(100.8, within(1e-9));
        // imbalance = (7 - 3) / 10
        assertThat(c.imbalance()).isCloseTo(0.4, within(1e-9));
    }

    @Test
    void emptyAccumulatorIsZeroed() {
        WindowStatsAccumulator acc = new WindowStatsAccumulator();
        assertThat(acc.isEmpty()).isTrue();
        assertThat(acc.vwap()).isZero();
        assertThat(acc.imbalance()).isZero();
    }

    @Test
    void mergeCombinesTwoAccumulators() {
        WindowStatsAccumulator a = new WindowStatsAccumulator();
        a.add(100, 2, true);
        a.add(105, 1, true);   // high 105

        WindowStatsAccumulator b = new WindowStatsAccumulator();
        b.add(95, 3, false);   // low 95
        b.add(101, 2, false);

        a.merge(b);
        Candle c = a.toCandle("X", 0, 1);
        assertThat(c.high()).isEqualTo(105);
        assertThat(c.low()).isEqualTo(95);
        assertThat(c.volume()).isEqualTo(8);
        assertThat(c.buyVolume()).isEqualTo(3);
        assertThat(c.sellVolume()).isEqualTo(5);
    }
}
