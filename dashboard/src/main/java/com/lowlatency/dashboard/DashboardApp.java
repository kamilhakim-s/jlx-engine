package com.lowlatency.dashboard;

/**
 * Entry point for the live dashboard (Chunk 10). Starts the engine pipeline and the HTTP/SSE server, then
 * blocks. Open the printed URL in a browser.
 *
 * <pre>./gradlew :dashboard:run            # then open http://localhost:8080
 * ./gradlew :dashboard:run --args="9090"  # custom port</pre>
 *
 * The dashboard attaches past the streaming seam, so it never touches the matching hot path. Live Binance
 * trades drive the market panels; toggle <b>stress</b> in the UI to drive synthetic load so the latency /
 * throughput / GC panels show the engine genuinely under load.
 */
public final class DashboardApp {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0].trim()) : 8080;

        SseHub hub = new SseHub();
        EngineHost engineHost = new EngineHost(hub);
        DashboardServer server = new DashboardServer(port, hub, engineHost);

        engineHost.start();
        server.start();

        System.out.printf("Low-latency dashboard running → http://localhost:%d%n", port);
        System.out.println("  (live Binance feed if online; toggle 'stress' in the UI to drive load)");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            engineHost.shutdown();
        }));

        Thread.currentThread().join(); // run until killed
    }

    private DashboardApp() {
    }
}
