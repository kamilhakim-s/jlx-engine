package com.lowlatency.marketdata;

/**
 * Converts a decimal string like {@code "42123.45"} into a scaled {@code long} (integer money) without
 * ever touching {@code double}. This preserves the Chunk 1 discipline: prices and quantities arrive
 * from Binance as decimal strings, but the engine only ever deals in integer ticks/units.
 *
 * <p>"Scale" is the number of decimal places retained. {@code parseScaled("42123.45", 2) == 4_212_345}.
 * Fractional digits beyond the scale are <b>truncated</b> (floored toward zero), matching how exchanges
 * round to a tick. Parsing is done by integer arithmetic on substrings, so it is exact and allocation-
 * light.
 */
public final class Decimals {

    private static final long[] POW10 = new long[19];

    static {
        long p = 1;
        for (int i = 0; i < POW10.length; i++) {
            POW10[i] = p;
            p *= 10;
        }
    }

    /** Returns {@code 10^scale} for {@code 0 <= scale <= 18}. */
    public static long pow10(int scale) {
        return POW10[scale];
    }

    /**
     * Parses {@code value} into an integer with {@code scale} implied decimal places.
     *
     * @throws NumberFormatException if the input is not a plain decimal number
     */
    public static long parseScaled(String value, int scale) {
        boolean negative = !value.isEmpty() && value.charAt(0) == '-';
        int start = negative ? 1 : 0;

        int dot = value.indexOf('.', start);
        long magnitude;
        if (dot < 0) {
            magnitude = parseDigits(value, start, value.length()) * POW10[scale];
        } else {
            long intPart = parseDigits(value, start, dot);
            long fracPart = scaledFraction(value, dot + 1, value.length(), scale);
            magnitude = intPart * POW10[scale] + fracPart;
        }
        return negative ? -magnitude : magnitude;
    }

    /** Takes the first {@code scale} fractional digits, padding with trailing zeros if shorter. */
    private static long scaledFraction(String value, int from, int to, int scale) {
        if (scale == 0) {
            return 0;
        }
        int available = to - from;
        if (available >= scale) {
            return parseDigits(value, from, from + scale);     // truncate extra digits
        }
        return parseDigits(value, from, to) * POW10[scale - available]; // pad short fractions
    }

    private static long parseDigits(String value, int from, int to) {
        if (from >= to) {
            return 0;
        }
        long result = 0;
        for (int i = from; i < to; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw new NumberFormatException("Not a decimal: '" + value + "'");
            }
            result = result * 10 + (c - '0');
        }
        return result;
    }

    private Decimals() {
    }
}
