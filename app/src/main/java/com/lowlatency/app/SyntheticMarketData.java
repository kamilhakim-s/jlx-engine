package com.lowlatency.app;

import com.lowlatency.marketdata.AggTrade;

import java.util.Random;
import java.util.function.Consumer;

/**
 * A deterministic, infra-free source of {@link AggTrade}s for the capstone demo. It produces the same
 * normalised shape Chunk 4 emits from real Binance data — a random-walk price with random sizes and
 * alternating aggressor side — so the rest of the pipeline can't tell synthetic data from the real feed.
 *
 * <p>Why synthetic? The capstone is meant to run anywhere with no network and no Docker. Point the very
 * same downstream wiring at {@code BinanceHistoricalDownloader.stream(...)} or {@code BinanceLiveClient}
 * (Chunk 4) and it consumes the real market instead — the source is the only thing that changes.
 *
 * <p>Seeded for reproducibility: the same seed yields the same trade stream, which (combined with the
 * deterministic engine) makes a run repeatable — the property the Chunk 5 journal/replay relies on.
 */
public final class SyntheticMarketData {

    private final Random random;
    private final long maxStepTicks;
    private final long maxQtyUnits;
    private long price;
    private long aggTradeId;

    public SyntheticMarketData(long seed, long startPriceTicks, long maxStepTicks, long maxQtyUnits) {
        this.random = new Random(seed);
        this.price = startPriceTicks;
        this.maxStepTicks = maxStepTicks;
        this.maxQtyUnits = maxQtyUnits;
    }

    /** Produces the next trade in the walk, stamped with {@code timeMillis} as its event time. */
    public AggTrade nextTrade(long timeMillis) {
        // Random walk, clamped so price never wanders non-positive.
        long step = random.nextLong(-maxStepTicks, maxStepTicks + 1);
        price = Math.max(1, price + step);
        long qty = 1 + random.nextLong(maxQtyUnits);
        boolean buyerIsMaker = random.nextBoolean();
        return new AggTrade(++aggTradeId, price, qty, timeMillis, buyerIsMaker);
    }

    /**
     * Generates {@code count} trades and hands each to {@code sink}. Event time advances by one
     * millisecond per trade from {@code baseTimeMillis}, giving the windowed analytics a clean event-time
     * axis independent of how fast we actually push.
     */
    public void generate(int count, long baseTimeMillis, Consumer<AggTrade> sink) {
        for (int i = 0; i < count; i++) {
            sink.accept(nextTrade(baseTimeMillis + i));
        }
    }

    /** Number of trades produced so far. */
    public long produced() {
        return aggTradeId;
    }
}
