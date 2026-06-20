package com.lowlatency.streaming;

import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;

import java.time.Duration;

/**
 * The streaming analytics pipeline, expressed as a pure stream-to-stream transformation so it can be
 * reused by both the production job ({@link TradeAnalyticsJob}, sourced from Kafka) and the unit test
 * (sourced from an in-memory list on a local Flink cluster). Keeping the topology separate from the
 * source/sink is what makes a Flink job testable.
 */
public final class TradeAnalytics {

    /**
     * Groups trades by symbol and computes one {@link Candle} per {@code windowSize} tumbling
     * <b>event-time</b> window.
     */
    public static DataStream<Candle> candles(DataStream<TradeEvent> trades, Duration windowSize) {
        return trades
                .keyBy(TradeEvent::symbol)
                .window(TumblingEventTimeWindows.of(windowSize))
                .aggregate(new WindowStatsAggregator(), new WindowFinalizer());
    }

    private TradeAnalytics() {
    }
}
