package com.lowlatency.marketdata;

/**
 * The decimal scales used to turn a symbol's prices and quantities into integer ticks/units.
 *
 * <p>Different instruments quote to different precisions; on Binance these come from the symbol's
 * {@code PRICE_FILTER.tickSize} and {@code LOT_SIZE.stepSize}. For learning we hard-code sensible
 * defaults for BTCUSDT ({@code priceScale=2} ⇒ 0.01 USDT ticks, {@code qtyScale=8} ⇒ 1e-8 BTC units),
 * which comfortably cover real values without loss.
 *
 * @param priceScale decimal places kept for price
 * @param qtyScale   decimal places kept for quantity
 */
public record SymbolScale(int priceScale, int qtyScale) {

    /** Reasonable default for BTCUSDT-like pairs. */
    public static final SymbolScale BTCUSDT = new SymbolScale(2, 8);

    public long priceToTicks(String price) {
        return Decimals.parseScaled(price, priceScale);
    }

    public long quantityToUnits(String quantity) {
        return Decimals.parseScaled(quantity, qtyScale);
    }
}
