# low-latency — a Java low-latency crypto order matching engine

A hands-on learning project for pivoting from Spring Boot microservices into **low-latency Java**.
It builds, chunk by chunk, the canonical low-latency system: an **order matching engine** fed by real
**Binance** market data, with a **Kafka + Flink** streaming analytics tier layered on top.

The project is built in self-contained chunks; each chunk ships working code **and** a `docs/NN-*.md`
file teaching the concepts behind it. See the progress checklist below for the full roadmap.

> **New here? Start with the [Low-Latency Java Guide](docs/LOW-LATENCY-JAVA-GUIDE.md)** — one document that
> explains every chunk's design and algorithms end to end, with deep dives on managing race conditions
> without locks and on streaming-data manipulation. The per-chunk `docs/0N-*.md` files are the detailed
> companions.

## Requirements
- **Java 21** (LTS) on your `PATH`
- **Gradle** — use the bundled wrapper (`./gradlew`); no system Gradle needed
- **Docker** — only from Chunk 6 onward (Kafka + Flink)

## Quick start
```bash
./gradlew :benchmarks:run    # Chunk 0: the coordinated-omission / tail-latency demo
./gradlew :benchmarks:jmh    # Chunk 0: JMH microbenchmarks
./gradlew :tuning:run        # Chunk 7: end-to-end latency suite (wait-strategy matrix)
./gradlew :tuning:benchEpsilon  # Chunk 7: prove the hot path is allocation-free (no-op GC)
./gradlew :gateway:run       # Chunk 8: orders over Aeron → gateway → engine (end-to-end latency)
./gradlew :app:run           # Chunk 9: the whole pipeline end-to-end (market data → … → candles)
./gradlew :dashboard:buildWeb && ./gradlew :dashboard:run   # Chunk 10: live web dashboard → localhost:8080
./gradlew build              # compile + test everything
```

## Live dashboard (the product)

A real-time web console that makes the engine's invisible nanosecond work **visible**: live latency
percentiles (p50 → p99.99), throughput, GC/allocation, an OHLC candlestick + VWAP, the trade tape, and
trade-flow imbalance — fed by the **live Binance** stream, with a **stress** toggle that drives synthetic
load so the latency panels show the engine under pressure.

- **▶ Hosted demo (no install):** a recorded session replayed client-side, deployed to GitHub Pages by
  [`.github/workflows/pages.yml`](.github/workflows/pages.yml) (enable Pages → "GitHub Actions").
- **Run it locally:** `./gradlew :dashboard:buildWeb && ./gradlew :dashboard:run`, then open
  http://localhost:8080 and click **Drive stress load**.
- **Frontend dev (hot reload):** `cd dashboard-web && npm install && npm run dev` (Vite on :5173 proxies
  the API/SSE to the Java backend on :8080).

The dashboard attaches **past the `AsyncTradeForwarder` seam** — it never touches the matching hot path.
Design notes in [docs/10-dashboard.md](docs/10-dashboard.md).

## Progress
- [x] **Chunk 0** — Foundations & measurement harness · [docs](docs/00-foundations.md)
- [x] **Chunk 1** — Order book & matching (correct, single-threaded) · [docs](docs/01-order-book.md)
- [x] **Chunk 2** — Zero-allocation & data-structure optimisation · [docs](docs/02-zero-gc.md)
- [x] **Chunk 3** — The Disruptor: single-writer engine · [docs](docs/03-disruptor.md)
- [x] **Chunk 4** — Market data ingestion from Binance · [docs](docs/04-market-data.md)
- [x] **Chunk 5** — Journaling & deterministic replay (Chronicle Queue) · [docs](docs/05-journaling.md)
- [x] **Chunk 6** — Streaming analytics (Kafka + Flink) · [docs](docs/06-streaming.md)
- [x] **Chunk 7** — Benchmarking, tuning & observability · [docs](docs/07-tuning.md)
- [x] **Chunk 8** — Aeron transport + order-entry gateway · [docs](docs/08-gateway.md)
- [x] **Chunk 9** — Capstone: full end-to-end pipeline · [docs](docs/09-capstone.md)
- [x] **Chunk 10** — Live web dashboard (the product) · [docs](docs/10-dashboard.md)

## Module layout (grows each chunk)
| Module | Purpose | Added in |
|---|---|---|
| `benchmarks` | JMH suites + measurement demos (HdrHistogram) | Chunk 0 |
| `engine-core` | Domain model, order book, matching (+ zero-alloc engine) | Chunk 1–2 |
| `engine-disruptor` | Disruptor ring-buffer wiring (single-writer service) | Chunk 3 |
| `market-data` | Binance bulk history + WebSocket live + replay | Chunk 4 |
| `journal` | Chronicle Queue journaling + deterministic replay | Chunk 5 |
| `streaming` | Kafka publisher + Flink windowed analytics | Chunk 6 |
| `tuning` | Latency benchmark suite + GC experiments + CPU affinity | Chunk 7 |
| `gateway` | Aeron transport + order-entry gateway | Chunk 8 |
| `app` | Capstone: full pipeline wired end-to-end | Chunk 9 |
| `dashboard` + `dashboard-web` | Live web console: SSE backend + React/Vite SPA | Chunk 10 |
