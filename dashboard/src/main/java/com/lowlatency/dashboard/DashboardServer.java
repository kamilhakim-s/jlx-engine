package com.lowlatency.dashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * The HTTP front door, built on the JDK's own {@link HttpServer} — no web framework. Routes:
 *
 * <ul>
 *   <li>{@code GET /api/stream} — the Server-Sent Events feed of dashboard frames ({@link SseHub}).</li>
 *   <li>{@code POST /api/control} — JSON {@code {stress, rate}} to toggle the load generator.</li>
 *   <li>{@code GET /healthz} — liveness probe.</li>
 *   <li>{@code GET /…} — the built SPA, served from classpath resources under {@code /web} (unknown
 *       non-API paths fall back to {@code index.html} so client-side routing works).</li>
 * </ul>
 *
 * <p>Each SSE connection holds one server thread (parked on its queue), so the executor pool is sized for
 * a handful of concurrent dashboards plus control/asset requests.
 */
final class DashboardServer {

    private final HttpServer server;
    private final SseHub hub;
    private final EngineHost engineHost;

    /** A control request body: both fields optional. */
    record ControlRequest(Boolean stress, Long rate) {
    }

    DashboardServer(int port, SseHub hub, EngineHost engineHost) throws IOException {
        this.hub = hub;
        this.engineHost = engineHost;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(32, r -> {
            Thread t = new Thread(r, "dashboard-http");
            t.setDaemon(true);
            return t;
        }));

        server.createContext("/api/stream", this::handleStream);
        server.createContext("/api/control", this::handleControl);
        server.createContext("/healthz", ex -> respond(ex, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/", this::handleStatic);
    }

    void start() {
        server.start();
    }

    /** The bound port (useful when constructed with port 0 for tests). */
    int port() {
        return server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain", "method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        hub.serve(exchange); // blocks this thread until the client disconnects
    }

    private void handleControl(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            respond(exchange, 204, "text/plain", new byte[0]);
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain", "method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = exchange.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            ControlRequest req = Json.fromJson(body, ControlRequest.class);
            engineHost.control(req.stress(), req.rate());
            respond(exchange, 204, "text/plain", new byte[0]);
        } catch (Exception e) {
            respond(exchange, 400, "text/plain", ("bad request: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path == null || path.equals("/") || path.isBlank()) {
            path = "/index.html";
        }
        byte[] body = readResource("/web" + path);
        if (body == null) {
            // SPA fallback: serve index.html for unknown non-asset paths (client-side routing).
            body = readResource("/web/index.html");
            path = "/index.html";
            if (body == null) {
                respond(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
        }
        respond(exchange, 200, contentType(path), body);
    }

    private static byte[] readResource(String resourcePath) {
        try (InputStream in = DashboardServer.class.getResourceAsStream(resourcePath)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } else {
            exchange.close();
        }
    }
}
