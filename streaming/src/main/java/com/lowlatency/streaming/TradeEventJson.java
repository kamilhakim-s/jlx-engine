package com.lowlatency.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * JSON codec for {@link TradeEvent} on the Kafka wire. JSON is chosen for legibility while learning
 * (you can {@code kafka-console-consumer} the topic and read it); a production system would prefer a
 * compact schema-evolving format like Avro or Protobuf. Jackson maps records natively.
 */
public final class TradeEventJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String toJson(TradeEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static TradeEvent fromJson(String json) {
        try {
            return MAPPER.readValue(json, TradeEvent.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Bad TradeEvent JSON: " + json, e);
        }
    }

    private TradeEventJson() {
    }
}
