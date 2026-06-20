package com.lowlatency.streaming;

import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

/**
 * Turns the incrementally-aggregated {@link WindowStatsAccumulator} into a final {@link Candle},
 * attaching the window boundaries and the key (symbol) that the {@link WindowStatsAggregator} alone
 * doesn't have access to. Pairing an {@code AggregateFunction} with a {@code ProcessWindowFunction}
 * like this is the idiomatic Flink pattern for "incremental aggregation + window metadata".
 */
public final class WindowFinalizer
        extends ProcessWindowFunction<WindowStatsAccumulator, Candle, String, TimeWindow> {

    @Override
    public void process(String symbol, Context context,
                        Iterable<WindowStatsAccumulator> aggregated, Collector<Candle> out) {
        WindowStatsAccumulator acc = aggregated.iterator().next();
        TimeWindow window = context.window();
        out.collect(acc.toCandle(symbol, window.getStart(), window.getEnd()));
    }
}
