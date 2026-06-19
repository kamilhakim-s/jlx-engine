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
./gradlew build              # compile + test everything
```

## Progress
- [x] **Chunk 0** — Foundations & measurement harness · [docs](docs/00-foundations.md)
- [ ] Chunk 1 — Order book & matching (correct, single-threaded)
- [ ] Chunk 2 — Zero-allocation & data-structure optimisation
- [ ] Chunk 3 — The Disruptor: single-writer engine
- [ ] Chunk 4 — Market data ingestion from Binance
- [ ] Chunk 5 — Journaling & deterministic replay (Chronicle Queue)
- [ ] Chunk 6 — Streaming analytics (Kafka + Flink)
- [ ] Chunk 7 — Benchmarking, tuning & observability
- [ ] Chunk 8 — *(optional)* Aeron transport + gateway
- [ ] Chunk 9 — *(optional)* Capstone: end-to-end demo

## Module layout (grows each chunk)
| Module | Purpose | Added in |
|---|---|---|
| `benchmarks` | JMH suites + measurement demos (HdrHistogram) | Chunk 0 |
| `engine-core` | Domain model, order book, matching | Chunk 1 |
| `engine-disruptor` | Disruptor ring-buffer wiring | Chunk 3 |
| `market-data` | Binance REST history + WebSocket live + replay | Chunk 4 |
| `journal` | Chronicle Queue journaling + replay | Chunk 5 |
| `streaming` | Kafka producers + Flink jobs | Chunk 6 |
| `gateway` | Aeron transport + order-entry gateway | Chunk 8 |
| `app` | End-to-end wiring | Chunk 9 |
