# Chunk 10 — The Live Dashboard (turning the engine into a product)

> **Running documentation.** Every previous chunk printed numbers to a terminal. Chunk 10 makes the
> engine's invisible, nanosecond-scale work **visible** as a real-time web console — latency percentiles,
> throughput, GC/allocation, candles, the trade tape — and packages it as something you can show: run it
> locally against the live Binance feed, or open a recorded session straight from GitHub Pages. The whole
> point of the design is that it does all this **without ever touching the matching hot path.**

---

## What we built

```
dashboard/src/main/java/com/lowlatency/dashboard/
├─ DashboardApp.java     entry point: starts the engine pipeline + HTTP/SSE server
├─ EngineHost.java       wires engine (MULTI producer) + live Binance feed + load generator + sampler
├─ MarketState.java      past-the-seam aggregator: tumbling candles + recent-trade tape
├─ MetricsSampler.java   samples latency (SingleWriterRecorder) + throughput + GC/alloc every 250 ms
├─ LoadGenerator.java    toggleable synthetic load so the latency panels show the engine under pressure
├─ SseHub.java           broadcasts JSON frames to browsers over Server-Sent Events (drop-on-overflow)
├─ DashboardServer.java  JDK HttpServer: /api/stream (SSE), /api/control, serves the SPA
├─ Frames.java / Json.java   the frame protocol + Jackson serialisation
├─ FrameRecorder.java    tees frames to .ndjson for the hosted demo
└─ DemoRecorder.java     headless capture of a session (no browser) → demo-frames.ndjson
dashboard-web/           React + Vite + TypeScript SPA (uPlot + lightweight-charts)
```

Run it:
```bash
./gradlew :dashboard:buildWeb && ./gradlew :dashboard:run   # → http://localhost:8080
cd dashboard-web && npm run dev                              # frontend hot-reload (Vite :5173 → :8080)
./gradlew :dashboard:recordDemo                              # capture a session for the Pages demo
./gradlew :dashboard:test                                   # backend unit + SSE integration tests
```

---

## 1. The cardinal rule: attach past the seam

The dashboard must show live engine internals **without slowing the engine down**. The architecture from
Chunk 6 already gives us the place to attach: the `AsyncTradeForwarder` **seam**. The dashboard is just
another consumer past that seam — exactly like Kafka was — so trades reach it on the forwarder thread, not
the engine thread.

```
 Binance @aggTrade ─┐
                    ├─► DisruptorMatchingService ──► SingleWriterRecorder (latency)
 LoadGenerator ─────┘        │ tradeListener
                             ▼
              AsyncTradeForwarder seam ──► MarketState ──► candle / tape frames
                             MetricsSampler ──► metrics frames (latency + throughput + GC)
                             ▼
                          SseHub ──► browser (Server-Sent Events)
```

Nothing the browser does — connecting, lagging, disconnecting — can reach the matcher. Even a slow browser
is absorbed by a **bounded per-client queue that drops frames** rather than backing up the broadcaster
(`SseHub`), the same drop-on-overflow backpressure the streaming seam uses.

## 2. Reading latency live without a data race — `SingleWriterRecorder`

The engine records each order's end-to-end latency on its single consumer thread. The dashboard wants to
read percentiles ~4×/second from a *different* thread. A plain `HdrHistogram.Histogram` can't be read while
it's being written — that's a data race.

The fix is the textbook tool, and it rhymes with the whole project: HdrHistogram's **`SingleWriterRecorder`**
is built for exactly **one writer** plus a separate sampling reader. The writer (our single engine
consumer) calls `recordValue`; the sampler calls `getIntervalHistogram()`, a phased swap that hands back the
values since the last call with no lock and no disturbance to the writer. We added a recorder path to
`MatchingEngineEventHandler`/`DisruptorMatchingService` for this — the **single-writer principle** (Chunk 3)
applied once more, now to observability. Because the interval resets each sample, the panel shows *latency
right now*, not a lifetime average.

## 3. The honest part: live data is too slow to stress the engine

This is the most important design note. Live Binance trades arrive a few-to-hundreds per second. At that
rate the engine is **idle**, and its latency *tail* is dominated by scheduling and JIT, not by load — which
would *undersell* an engine that does millions of orders/sec. A dashboard that showed only live-feed
latency would be quietly dishonest.

So the dashboard separates the two concerns:

- **Live Binance drives the market panels** — real prices, real candles, real tape.
- **A toggleable [`LoadGenerator`](../dashboard/src/main/java/com/lowlatency/dashboard/LoadGenerator.java)
  drives synthetic high-rate order flow** into the *same* engine, so the latency / throughput / GC panels
  show the engine genuinely **under load**. It walks its synthetic price around the live price so the chart
  stays coherent when you toggle stress.

Because two threads now publish into the ring (the live-feed thread and the load generator), the engine is
built with `ProducerType.MULTI`. The UI's **Drive stress load** button posts to `/api/control`; flip it on
and you watch p99.9 settle into a tight band and throughput jump to hundreds of thousands/sec — the real
story, told honestly.

## 4. Transport: SSE over the JDK's own HttpServer

The backend pushes frames as **Server-Sent Events** — a one-way text stream over plain HTTP, perfect for a
dashboard and far simpler than WebSockets. No web framework: the JDK's `com.sun.net.httpserver.HttpServer`
serves `/api/stream` (SSE), `/api/control` (POST), and the built SPA. Frames are bounded by design so the
browser is never flooded under load: `metrics` and `tape` on a 250 ms timer, `candle` once per closed
window, `status` on change — individual trades are *sampled* into the tape, never streamed one-per-event.

## 5. The frontend: React + Vite, charts that handle the firehose

[`dashboard-web/`](../dashboard-web) is a React + TypeScript app built with Vite. Two charting libraries,
each chosen for the job: **uPlot** (tiny, canvas, very fast) for the latency-percentile and throughput
time-series that update 4×/sec on a log scale, and **lightweight-charts** (TradingView) for the OHLC
candlestick + VWAP. Charts update **imperatively** via refs, so high-frequency data doesn't thrash React's
render. A single `useFrames` hook owns an `EventSource` and dispatches frames into state.

## 6. The hosted demo: a recorded session, replayed client-side

A live dashboard needs a running JVM and a socket — neither exists on a static page. So for the GitHub
Pages demo, [`DemoRecorder`](../dashboard/src/main/java/com/lowlatency/dashboard/DemoRecorder.java) captures
a real session (idle baseline → stress on) to `demo-frames.ndjson` via `FrameRecorder` (which tees every
broadcast frame to disk, independent of any browser). Built with `VITE_DEMO=1`, the *same* SPA replays that
recording on a timer instead of opening an `EventSource` — identical components, no backend. The Pages
workflow ([`.github/workflows/pages.yml`](../.github/workflows/pages.yml)) builds and deploys it, so anyone
can open the live console straight from the repo link.

## 7. What was verified

- **`MarketStateTest`** — tumbling-window OHLC, the bounded recent-trade buffer, last-price tracking.
- **`FramesJsonTest`** — frames serialise to the exact field names the SPA reads; control bodies parse.
- **`DashboardServerIntegrationTest`** — boots the whole backend on an ephemeral port, turns on stress via
  `/api/control`, and asserts a live `metrics` frame arrives over SSE with non-zero throughput — the full
  path engine → recorder/sampler → SSE → client, offline (no network needed; stress drives the engine).

Verified by hand too: against the **live Binance feed** the latency chart shows the idle→under-load
transition the moment stress is toggled (p50 ≈ 1 µs, ~500k orders/s), with zero browser console errors; and
the recorded demo replays the same session as a static page.

---

## Key takeaways

1. **Observe past the seam.** The dashboard is just another consumer of the `AsyncTradeForwarder` stream;
   the matching hot path is never touched off-thread. A slow browser drops frames, it never stalls the
   engine.
2. **`SingleWriterRecorder`** lets one thread sample latency percentiles live while the single engine
   consumer keeps writing — the single-writer principle, applied to observability.
3. **Be honest about load.** Live market data is too slow to stress the engine, so a synthetic load
   generator drives the latency/GC panels — the UI says exactly when it's on.
4. **SSE + the JDK HttpServer** is all the transport a dashboard needs — no framework, dependency-light,
   consistent with the project's JDK-first ethos.
5. **A recorded session** makes the live console a static, hostable product: same SPA, frames replayed from
   `.ndjson`, deployable to GitHub Pages with no JVM.

This is the capstone-of-the-capstone: the nanosecond engine from Chunks 1–9, now a thing you can *see* and
*show*.
