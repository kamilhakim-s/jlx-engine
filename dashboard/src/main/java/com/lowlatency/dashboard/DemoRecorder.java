package com.lowlatency.dashboard;

import java.nio.file.Path;

/**
 * Captures a self-contained dashboard session to an {@code .ndjson} file for the GitHub Pages demo — no
 * HTTP server, no browser. It boots the real engine pipeline, lets the live feed (if online) and the idle
 * baseline play for a few seconds, then switches the stress load on so the recording shows the headline
 * idle→under-load transition. The frontend's demo mode replays the result.
 *
 * <pre>./gradlew :dashboard:recordDemo                    # writes dashboard-web/public/demo-frames.ndjson
 * ./gradlew :dashboard:run -PdemoArgs="out.ndjson 60"  # custom (handled by the task)</pre>
 */
public final class DemoRecorder {

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "dashboard-web/public/demo-frames.ndjson");
        long seconds = args.length > 1 ? Long.parseLong(args[1].trim()) : 45;

        SseHub hub = new SseHub();
        try (FrameRecorder recorder = new FrameRecorder(out)) {
            hub.setRecorder(recorder);
            EngineHost host = new EngineHost(hub);
            host.start();
            System.out.printf("Recording %ds → %s%n", seconds, out.toAbsolutePath());

            Thread.sleep(6_000);                 // idle / live-only baseline
            host.control(true, 250_000L);        // stress on — the under-load segment
            System.out.println("  stress on (250k/s)…");
            Thread.sleep(Math.max(0, (seconds - 6) * 1000));

            host.shutdown();
        }
        System.out.println("Recording complete.");
        System.exit(0); // engine daemon threads otherwise keep the JVM alive
    }

    private DemoRecorder() {
    }
}
