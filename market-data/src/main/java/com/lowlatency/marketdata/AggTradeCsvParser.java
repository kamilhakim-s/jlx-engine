package com.lowlatency.marketdata;

/**
 * Parses one line of a Binance historical aggregate-trades CSV (from data.binance.vision) into an
 * {@link AggTrade}. Column layout:
 *
 * <pre>aggTradeId, price, quantity, firstTradeId, lastTradeId, transactTime, isBuyerMaker, isBestMatch</pre>
 *
 * <p>Some daily files include a header row and some don't; a line whose first field isn't numeric is
 * treated as a header and skipped (returns {@code null}). Binance switched {@code transactTime} from
 * milliseconds to microseconds in newer datasets, so we normalise anything with more than 13 digits
 * back to millis.
 *
 * <p>This runs in the ingestion tier, not the engine hot path, so the per-line {@code split} is fine —
 * the strict no-allocation rule only applies inside the matching engine.
 */
public final class AggTradeCsvParser {

    private static final long MAX_MILLIS_13_DIGITS = 9_999_999_999_999L;

    /** @return the parsed trade, or {@code null} for a header/blank line. */
    public AggTrade parseLine(String line, SymbolScale scale) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] f = line.split(",");
        if (f.length < 7) {
            return null;
        }
        long aggTradeId;
        try {
            aggTradeId = Long.parseLong(f[0].trim());
        } catch (NumberFormatException headerRow) {
            return null;
        }
        long priceTicks = scale.priceToTicks(f[1].trim());
        long quantityUnits = scale.quantityToUnits(f[2].trim());
        long timestamp = normaliseToMillis(Long.parseLong(f[5].trim()));
        boolean buyerIsMaker = parseBool(f[6].trim());
        return new AggTrade(aggTradeId, priceTicks, quantityUnits, timestamp, buyerIsMaker);
    }

    private static long normaliseToMillis(long transactTime) {
        return transactTime > MAX_MILLIS_13_DIGITS ? transactTime / 1000 : transactTime;
    }

    private static boolean parseBool(String s) {
        return s.equalsIgnoreCase("true") || s.equals("1");
    }
}
