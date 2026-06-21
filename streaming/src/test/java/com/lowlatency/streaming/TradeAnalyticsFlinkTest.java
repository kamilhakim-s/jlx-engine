package com.lowlatency.streaming;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Exercises the real Flink windowing pipeline on an embedded local cluster with an in-memory bounded
 * source — no Kafka, no Docker. A bounded source emits a final watermark at +∞ on completion, which
 * fires all event-time windows, so we can assert the candles deterministically.
 */
class TradeAnalyticsFlinkTest {

    @Test
    void tumblingEventTimeWindowsProduceCorrectCandles() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);

        // Two 10s windows: [0,10000) gets seq 1-3; [10000,20000) gets seq 4.
        List<TradeEvent> input = List.of(
                new TradeEvent("BTCUSDT", 1, 100, 2, true, 1_000),
                new TradeEvent("BTCUSDT", 2, 102, 3, false, 2_000),
                new TradeEvent("BTCUSDT", 3, 98, 1, true, 9_000),
                new TradeEvent("BTCUSDT", 4, 105, 5, true, 12_000));

        DataStream<TradeEvent> trades = env.fromData(input)
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy.<TradeEvent>forMonotonousTimestamps()
                                .withTimestampAssigner((event, ts) -> event.timestampMillis()));

        List<Candle> candles = new ArrayList<>();
        try (CloseableIterator<Candle> it =
                     TradeAnalytics.candles(trades, Duration.ofSeconds(10)).executeAndCollect()) {
            it.forEachRemaining(candles::add);
        }
        candles.sort(Comparator.comparingLong(Candle::windowStartMillis));

        assertThat(candles).hasSize(2);

        Candle first = candles.get(0);
        assertThat(first.windowStartMillis()).isEqualTo(0);
        assertThat(first.windowEndMillis()).isEqualTo(10_000);
        assertThat(first.open()).isEqualTo(100);
        assertThat(first.high()).isEqualTo(102);
        assertThat(first.low()).isEqualTo(98);
        assertThat(first.close()).isEqualTo(98);
        assertThat(first.volume()).isEqualTo(6);
        assertThat(first.buyVolume()).isEqualTo(3);  // seq 1 (2) + seq 3 (1)
        assertThat(first.sellVolume()).isEqualTo(3); // seq 2 (3)
        // VWAP = (100*2 + 102*3 + 98*1)/6 = 604/6
        assertThat(first.vwap()).isCloseTo(604.0 / 6, within(1e-9));

        Candle second = candles.get(1);
        assertThat(second.windowStartMillis()).isEqualTo(10_000);
        assertThat(second.open()).isEqualTo(105);
        assertThat(second.close()).isEqualTo(105);
        assertThat(second.volume()).isEqualTo(5);
        assertThat(second.imbalance()).isCloseTo(1.0, within(1e-9)); // all buy
    }
}
