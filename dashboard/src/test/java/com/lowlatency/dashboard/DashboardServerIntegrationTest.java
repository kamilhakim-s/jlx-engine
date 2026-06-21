package com.lowlatency.dashboard;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole backend (engine + SSE server) on an ephemeral port, turns on the stress load via the
 * control endpoint, and asserts a live {@code metrics} frame arrives with non-zero throughput — i.e. the
 * full path engine → recorder/sampler → SSE → client works. No network needed: stress drives the engine,
 * so this passes offline even though the live Binance connect is attempted in the background.
 */
class DashboardServerIntegrationTest {

    @Test
    void streamsLiveMetricsUnderStress() throws Exception {
        SseHub hub = new SseHub();
        EngineHost engineHost = new EngineHost(hub);
        DashboardServer server = new DashboardServer(0, hub, engineHost);
        engineHost.start();
        server.start();
        int port = server.port();
        HttpClient client = HttpClient.newHttpClient();

        try {
            // Turn on a modest synthetic load.
            HttpResponse<String> control = client.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/api/control"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"stress\":true,\"rate\":50000}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(control.statusCode()).isEqualTo(204);

            // Subscribe to the SSE stream and capture the first metrics frame showing orders flowing.
            AtomicReference<String> metricsData = new AtomicReference<>();
            CompletableFuture<Void> done = new CompletableFuture<>();
            client.sendAsync(HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/api/stream"))
                            .timeout(Duration.ofSeconds(15)).GET().build(),
                            HttpResponse.BodyHandlers.ofLines())
                    .thenAccept(resp -> {
                        String[] lastEvent = {""};
                        resp.body().forEach(line -> {
                            if (line.startsWith("event: ")) {
                                lastEvent[0] = line.substring(7).trim();
                            } else if (line.startsWith("data: ") && lastEvent[0].equals("metrics")) {
                                String data = line.substring(6);
                                if (data.contains("\"ordersPerSec\":") && !data.contains("\"ordersPerSec\":0.0")) {
                                    metricsData.set(data);
                                    done.complete(null);
                                }
                            }
                        });
                    });

            done.get(10, TimeUnit.SECONDS);
            String json = metricsData.get();
            assertThat(json).isNotNull();
            Frames.MetricsFrame frame = Json.fromJson(json, Frames.MetricsFrame.class);
            assertThat(frame.ordersPerSec()).isGreaterThan(0);
            assertThat(frame.processedTotal()).isGreaterThan(0);
        } finally {
            engineHost.shutdown();
            server.stop();
        }
    }
}
