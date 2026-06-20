package com.lowlatency.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Parses a live Binance {@code <symbol>@aggTrade} WebSocket message into an {@link AggTrade}. A message
 * looks like:
 *
 * <pre>{@code {"e":"aggTrade","E":1700000000000,"s":"BTCUSDT","a":12345,
 *      "p":"42123.45","q":"0.01","f":100,"l":105,"T":1700000000000,"m":true,"M":true}}</pre>
 *
 * We read {@code a} (id), {@code p} (price), {@code q} (quantity), {@code T} (trade time), and
 * {@code m} (buyer-is-maker). Jackson is used for robustness; the messages are tiny, and parsing
 * happens in the ingestion tier, off the engine's hot path.
 */
public final class AggTradeJsonParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public AggTrade parse(String json, SymbolScale scale) {
        try {
            JsonNode n = mapper.readTree(json);
            return new AggTrade(
                    n.get("a").asLong(),
                    scale.priceToTicks(n.get("p").asText()),
                    scale.quantityToUnits(n.get("q").asText()),
                    n.get("T").asLong(),
                    n.get("m").asBoolean());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse aggTrade JSON: " + json, e);
        }
    }
}
