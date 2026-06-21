package com.lowlatency.dashboard;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Broadcasts Server-Sent Events to every connected dashboard client. One {@link Client} per browser tab,
 * each with a <b>bounded</b> queue: if a client can't keep up, we <b>drop</b> frames for it (counting the
 * drops) rather than let a slow consumer back up the broadcaster — the same drop-on-overflow backpressure
 * the streaming seam uses (Chunk 6). The producer of frames (engine/metrics threads) is never blocked by a
 * slow browser.
 *
 * <p>Each SSE connection occupies one HTTP-server thread that parks on its queue and writes frames as they
 * arrive (plus a periodic heartbeat comment to keep proxies from closing an idle connection).
 */
final class SseHub {

    private static final byte[] HEARTBEAT = ":hb\n\n".getBytes(StandardCharsets.UTF_8);

    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private final AtomicLong dropped = new AtomicLong();
    private volatile FrameRecorder recorder; // optional: tee frames to an ndjson file for the hosted demo

    /** Tee every broadcast frame to {@code recorder} (used to capture the GitHub Pages demo recording). */
    void setRecorder(FrameRecorder recorder) {
        this.recorder = recorder;
    }

    /** Handles a {@code GET /api/stream} request: registers the client and writes frames until it leaves. */
    void serve(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache, no-transform");
        exchange.getResponseHeaders().add("Connection", "keep-alive");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*"); // dev: Vite :5173 → :8080
        exchange.sendResponseHeaders(200, 0);

        Client client = new Client();
        clients.add(client);
        OutputStream out = exchange.getResponseBody();
        try {
            // Greet so the client knows it's connected even before the first frame.
            out.write("event: hello\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (true) {
                byte[] frame = client.queue.poll(15, TimeUnit.SECONDS);
                out.write(frame != null ? frame : HEARTBEAT);
                out.flush();
            }
        } catch (IOException | InterruptedException closed) {
            // Browser navigated away / connection reset — fall through to cleanup.
        } finally {
            clients.remove(client);
            try {
                exchange.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    /** Serialises {@code payload} and pushes it to every client under the SSE event {@code event}. */
    void broadcast(String event, Object payload) {
        String json = Json.toJson(payload);
        FrameRecorder rec = recorder;
        if (rec != null) {
            rec.write(event, json); // recorded independently of clients (the demo capture has no browser)
        }
        if (clients.isEmpty()) {
            return;
        }
        byte[] bytes = ("event: " + event + "\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
        for (Client c : clients) {
            if (!c.queue.offer(bytes)) {
                dropped.incrementAndGet(); // slow client — drop rather than block the broadcaster
            }
        }
    }

    int clientCount() {
        return clients.size();
    }

    long droppedFrames() {
        return dropped.get();
    }

    private static final class Client {
        // Bounded: a few hundred frames of slack; beyond that the client is too slow and we drop.
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(256);
    }
}
