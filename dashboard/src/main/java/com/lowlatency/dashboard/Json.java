package com.lowlatency.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared Jackson mapper for serialising dashboard frames to JSON (mirrors streaming's TradeEventJson). */
final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("frame serialization failed", e);
        }
    }

    static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("frame deserialization failed", e);
        }
    }

    private Json() {
    }
}
