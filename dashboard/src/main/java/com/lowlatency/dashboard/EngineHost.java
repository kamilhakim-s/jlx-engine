package com.lowlatency.dashboard;

import com.lowlatency.engine.disruptor.DisruptorMatchingService;
import com.lowlatency.marketdata.BinanceLiveClient;
import com.lowlatency.marketdata.MarketDataReplayer;
import com.lowlatency.marketdata.SymbolScale;
import com.lowlatency.streaming.AsyncTradeForwarder;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.ProducerType;
import org.HdrHistogram.SingleWriterRecorder;

import java.net.http.WebSocket;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns and wires the whole live pipeline behind the dashboard:
 *
 * <pre>
 *   Binance @aggTrade ─┐
 *                      ├─► DisruptorMatchingService (MULTI producer) ─► SingleWriterRecorder (latency)
 *   LoadGenerator ─────┘                       │ tradeListener
 *                                              ▼
 *                              AsyncTradeForwarder seam ─► MarketState ─► candle/tape frames
 *                                              MetricsSampler ─► metrics frames
 * </pre>
 *
 * The engine uses {@link ProducerType#MULTI} because two threads publish into it — the live-feed thread and
 * the load generator. Everything the dashboard reads is taken <b>past the forwarder seam</b> or from the
 * thread-safe {@link SingleWriterRecorder}; the matching hot path is never touched off-thread.
 */
final class EngineHost {

    private static final String SYMBOL = "BTCUSDT";
    private static final int RING_SIZE = 1 << 16;
    private static final long MAX_LATENCY_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long WINDOW_MILLIS = 1_000;
    private static final long METRICS_INTERVAL_MILLIS = 250;

    private final SseHub hub;
    private final SingleWriterRecorder latencyRecorder =
            new SingleWriterRecorder(MAX_LATENCY_NANOS, 3);
    private final AsyncTradeForwarder forwarder;
    private final DisruptorMatchingService engine;
    private final MarketState marketState;
    private final LoadGenerator load;
    private final MetricsSampler sampler;
    private final BinanceLiveClient liveClient = new BinanceLiveClient();
    private final ScheduledExecutorService statusBeat =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "status-beat");
                t.setDaemon(true);
                return t;
            });

    private final long startMillis = System.currentTimeMillis();
    private volatile boolean live;
    private WebSocket webSocket;

    EngineHost(SseHub hub) {
        this.hub = hub;
        this.marketState = new MarketState(WINDOW_MILLIS, candle -> hub.broadcast("candle", candle));
        this.forwarder = new AsyncTradeForwarder(SYMBOL, marketState, 1 << 16, Clock.systemUTC());
        this.engine = new DisruptorMatchingService(
                RING_SIZE, ProducerType.MULTI, new YieldingWaitStrategy(),
                RING_SIZE, latencyRecorder, null, forwarder);
        this.load = new LoadGenerator(engine, marketState::lastPrice);
        this.sampler = new MetricsSampler(engine, latencyRecorder, marketState, hub, METRICS_INTERVAL_MILLIS);
    }

    void start() {
        engine.start();
        connectLiveFeed();          // best-effort; the dashboard still works (stress) if it fails
        load.start();
        sampler.start();
        statusBeat.scheduleAtFixedRate(this::broadcastStatus, 0, 2, TimeUnit.SECONDS);
    }

    /** Applies a control message from the UI: toggle stress and/or set its rate. */
    void control(Boolean stress, Long rate) {
        if (rate != null) {
            load.setRatePerSec(rate);
        }
        if (stress != null) {
            load.setEnabled(stress);
        }
        broadcastStatus();
    }

    void shutdown() {
        statusBeat.shutdownNow();
        sampler.stop();
        load.stop();
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (Exception ignored) {
                // best-effort
            }
        }
        forwarder.close();
        engine.shutdown();
    }

    private void connectLiveFeed() {
        // Connecting joins a WebSocket handshake (network); do it off the start() path so the dashboard
        // comes up instantly and an offline box still works (stress drives the engine).
        Thread t = new Thread(() -> {
            try {
                MarketDataReplayer replayer = new MarketDataReplayer(engine, true);
                webSocket = liveClient.connect(SYMBOL, SymbolScale.BTCUSDT, replayer);
                live = true;
                broadcastStatus();
            } catch (Throwable ignored) {
                live = false; // offline / no network — fine, the stress generator still drives the engine
            }
        }, "live-feed-connect");
        t.setDaemon(true);
        t.start();
    }

    private void broadcastStatus() {
        hub.broadcast("status", new Frames.StatusFrame(
                SYMBOL,
                live ? "Binance @aggTrade" : "offline (toggle stress for synthetic load)",
                load.isEnabled(), load.ratePerSec(),
                System.currentTimeMillis() - startMillis, live));
    }
}
