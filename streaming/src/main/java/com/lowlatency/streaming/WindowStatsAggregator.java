package com.lowlatency.streaming;

import org.apache.flink.api.common.functions.AggregateFunction;

/**
 * Flink {@link AggregateFunction} that folds each {@link TradeEvent} into a
 * {@link WindowStatsAccumulator}. Using an incremental aggregate (rather than buffering all trades and
 * computing at window close) means Flink keeps only the small accumulator in state per window — O(1)
 * memory regardless of how many trades land in the window.
 */
public final class WindowStatsAggregator
        implements AggregateFunction<TradeEvent, WindowStatsAccumulator, WindowStatsAccumulator> {

    @Override
    public WindowStatsAccumulator createAccumulator() {
        return new WindowStatsAccumulator();
    }

    @Override
    public WindowStatsAccumulator add(TradeEvent value, WindowStatsAccumulator acc) {
        acc.add(value);
        return acc;
    }

    @Override
    public WindowStatsAccumulator getResult(WindowStatsAccumulator acc) {
        return acc;
    }

    @Override
    public WindowStatsAccumulator merge(WindowStatsAccumulator a, WindowStatsAccumulator b) {
        return a.merge(b);
    }
}
