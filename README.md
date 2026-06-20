# low-latency — a Java low-latency crypto order matching engine

A hands-on learning project for pivoting from Spring Boot microservices into **low-latency Java**.
It builds, chunk by chunk, the canonical low-latency system: an **order matching engine** fed by real
**Binance** market data, with a **Kafka + Flink** streaming analytics tier layered on top.

The project is built in self-contained chunks; each chunk ships working code **and** a `docs/NN-*.md`
file teaching the concepts behind it. See the progress checklist below for the full roadmap.

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
./gradlew build              # compile + test everything
```

## Progress
- [x] **Chunk 0** — Foundations & measurement harness · [docs](docs/00-foundations.md)
- [x] **Chunk 1** — Order book & matching (correct, single-threaded) · [docs](docs/01-order-book.md)
- [x] **Chunk 2** — Zero-allocation & data-structure optimisation · [docs](docs/02-zero-gc.md)
- [x] **Chunk 3** — The Disruptor: single-writer engine · [docs](docs/03-disruptor.md)
- [x] **Chunk 4** — Market data ingestion from Binance · [docs](docs/04-market-data.md)
- [x] **Chunk 5** — Journaling & deterministic replay (Chronicle Queue) · [docs](docs/05-journaling.md)
- [x] **Chunk 6** — Streaming analytics (Kafka + Flink) · [docs](docs/06-streaming.md)
- [x] **Chunk 7** — Benchmarking, tuning & observability · [docs](docs/07-tuning.md)
- [ ] Chunk 8 — *(optional)* Aeron transport + gateway
- [ ] Chunk 9 — *(optional)* Capstone: end-to-end demo

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
| `app` | End-to-end wiring | Chunk 9 |
