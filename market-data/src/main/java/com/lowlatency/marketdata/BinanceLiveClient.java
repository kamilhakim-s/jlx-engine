package com.lowlatency.marketdata;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Streams <b>live</b> trades from Binance's public WebSocket ({@code <symbol>@aggTrade}) using the
 * JDK's {@link WebSocket} client — no third-party library. Each message is parsed into the same
 * {@link AggTrade} shape as the historical files, so the live and replay paths are interchangeable.
 *
 * <p>WebSocket text can arrive in fragments ({@code onText} may be called with {@code last == false}),
 * so we accumulate fragments per message and only parse on the final frame. After each delivery we
 * call {@code request(1)} to ask for the next message (the JDK client uses this for flow control /
 * backpressure at the socket level).
 */
public final class BinanceLiveClient {

    private static final String STREAM_BASE = "wss://stream.binance.com:9443/ws";

    private final HttpClient http = HttpClient.newHttpClient();
    private final AggTradeJsonParser parser = new AggTradeJsonParser();

    /**
     * Connects and delivers each live {@link AggTrade} to {@code onTrade}. The returned {@link WebSocket}
     * can be closed with {@code sendClose(...)} (or use {@link #streamFor}).
     */
    public WebSocket connect(String symbol, SymbolScale scale, Consumer<AggTrade> onTrade) {
        URI uri = URI.create(STREAM_BASE + "/" + symbol.toLowerCase() + "@aggTrade");
        return http.newWebSocketBuilder()
                .buildAsync(uri, new AggTradeListener(scale, onTrade))
                .join();
    }

    /** Connects, streams for {@code duration}, then closes cleanly. Returns when finished. */
    public void streamFor(String symbol, SymbolScale scale, Duration duration, Consumer<AggTrade> onTrade)
            throws InterruptedException {
        WebSocket ws = connect(symbol, scale, onTrade);
        try {
            Thread.sleep(duration.toMillis());
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    private final class AggTradeListener implements WebSocket.Listener {
        private final SymbolScale scale;
        private final Consumer<AggTrade> onTrade;
        private final StringBuilder message = new StringBuilder();

        AggTradeListener(SymbolScale scale, Consumer<AggTrade> onTrade) {
            this.scale = scale;
            this.onTrade = onTrade;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            message.append(data);
            if (last) {
                String complete = message.toString();
                message.setLength(0);
                try {
                    onTrade.accept(parser.parse(complete, scale));
                } catch (RuntimeException ignored) {
                    // Skip control frames / unexpected payloads; keep the stream alive.
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("WebSocket error: " + error.getMessage());
        }
    }
}
