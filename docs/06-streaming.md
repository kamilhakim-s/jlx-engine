# Chunk 6 — Streaming Analytics (Kafka + Flink)

> **Running documentation.** Chunk 6 adds the analytics tier: the engine publishes its trades to
> **Kafka** (off the hot path), and a **Flink** job computes live windowed analytics — OHLC candles,
> VWAP, and trade-flow imbalance — using event-time windows and watermarks. This tier lives *outside*
> the low-latency core on purpose, and this chunk is largely about *why*.

---

## What we built

```
streaming/src/main/java/com/lowlatency/streaming/
├─ TradeEvent.java            flat, serialisable trade (crosses the Kafka boundary)
├─ TradeEventJson.java        JSON codec for the wire
├─ WindowStatsAccumulator.java   pure integer OHLC/VWAP/volume/imbalance maths (no Flink)
├─ Candle.java                the windowed output
├─ WindowStatsAggregator.java Flink AggregateFunction driving the accumulator
├─ WindowFinalizer.java       ProcessWindowFunction attaching window start/end + symbol
├─ TradeAnalytics.java        the pipeline topology (testable, source/sink-agnostic)
├─ TradeAnalyticsJob.java     the Flink job: Kafka → windows → candles
├─ AsyncTradeForwarder.java   the boundary OUT of the LL core (engine thread → queue → forwarder thread)
├─ KafkaTradeSink.java        publishes TradeEvents to Kafka
└─ EngineToKafkaApp.java      runs an order flow through the engine → Kafka
streaming/docker-compose.yml  single-broker Kafka (KRaft)
```

Run the full pipeline (needs Docker):
```bash
docker compose -f streaming/docker-compose.yml up -d   # Kafka on localhost:9092
./gradlew :streaming:runProducer                        # engine trades → Kafka topic "trades"
./gradlew :streaming:run                                # Flink job → prints candles
docker compose -f streaming/docker-compose.yml down
```
Run the tests (no Docker, no Kafka):
```bash
./gradlew :streaming:test    # pure analytics maths + JSON + async forwarder + EMBEDDED Flink job
```

## 1. Why a separate tier — and where the seam is

The matching engine budgets in *nanoseconds*; Kafka and Flink work in *milliseconds*. You must never
let a broker round-trip, a serialisation, or a Flink checkpoint touch the engine thread. So Chunk 6
draws a hard architectural seam:

```
   ── low-latency core (Chunks 1–5) ──┊── streaming tier (Chunk 6) ──
 engine consumer thread → AsyncTradeForwarder ┊→ queue → forwarder thread → Kafka → Flink → candles
                                       ┊
                       (nanoseconds)   ┊      (milliseconds, durable, scalable)
```

[`AsyncTradeForwarder`](../streaming/src/main/java/com/lowlatency/streaming/AsyncTradeForwarder.java)
*is* that seam. The engine calls `onTrade` on its single consumer thread and does the bare minimum:
snapshot the trade into a `TradeEvent` and `offer` it to a bounded queue. A **separate forwarder
thread** drains the queue to Kafka. Network I/O and serialisation never run on the engine thread. If
the sink stalls, the bounded queue fills and we **drop** (counting drops) rather than block the engine
— one backpressure policy. This is the same lesson as Chunk 4 ("ingestion allocation becomes tail
latency"), now applied on the way *out*: keep everything that isn't matching off the matching thread.

> Note the honest trade-off: snapshotting into a `TradeEvent` allocates one small object per trade on
> the engine thread. That's acceptable *here* because we've explicitly left the zero-GC core — beyond
> the seam we optimise for throughput and operability, not nanoseconds.

## 2. Kafka as the async boundary (and vs Chronicle)

Kafka is a durable, partitioned, replayable log that decouples producers from consumers across the
network. We key each record by **symbol** so all of an instrument's trades land on one partition,
preserving per-symbol order. Contrast with Chunk 5's Chronicle Queue:

| | Chronicle Queue (Chunk 5) | Kafka (Chunk 6) |
|---|---|---|
| Purpose | in-process **recovery journal** | networked **fan-out** to analytics/consumers |
| Latency | sub-µs append on the box | ms, over the network |
| Scope | one engine, crash recovery | many independent consumers, scale-out |

They're complementary: journal on the hot path for recovery; publish to Kafka past the seam for
everyone else.

## 3. The Flink job — event time, windows, watermarks

[`TradeAnalyticsJob`](../streaming/src/main/java/com/lowlatency/streaming/TradeAnalyticsJob.java) reads
the `trades` topic and, per symbol, emits one [`Candle`](../streaming/src/main/java/com/lowlatency/streaming/Candle.java)
per 10-second window. The concepts this teaches:

- **Stream, not batch.** The job runs forever over an unbounded stream, emitting results continuously
  as windows close — not a one-shot pass over a finished dataset.
- **Event time vs processing time.** We window by **event time** (the trade's own `timestampMillis`),
  not by when Flink happens to see it. This makes results correct and reproducible regardless of
  network delay or replay speed — re-running the same data yields the same candles.
- **Watermarks.** A stream can't know it has seen *every* event for a window until time advances. A
  watermark is Flink's assertion "no more events older than T will arrive." We use
  `forBoundedOutOfOrderness(2s)`, tolerating 2 seconds of lateness before a window fires — the classic
  completeness-vs-latency dial.
- **Incremental aggregation.** [`WindowStatsAggregator`](../streaming/src/main/java/com/lowlatency/streaming/WindowStatsAggregator.java)
  folds each trade into a small accumulator (O(1) state per window) rather than buffering every trade;
  [`WindowFinalizer`](../streaming/src/main/java/com/lowlatency/streaming/WindowFinalizer.java) attaches
  the window boundaries and key at the end. Pairing `AggregateFunction` + `ProcessWindowFunction` is
  the idiomatic Flink pattern.
- **Exactly-once (in principle).** Flink's checkpointing + a transactional/idempotent sink gives
  exactly-once results; we keep the demo simple (at-least-once `print`), but the windowing is
  checkpoint-ready.

## 4. The analytics maths, kept testable

The aggregation lives in [`WindowStatsAccumulator`](../streaming/src/main/java/com/lowlatency/streaming/WindowStatsAccumulator.java)
as **plain Java with no Flink dependency** — the part most worth getting right. All sums are integer
(`vwapNumerator = Σ price·qty`, `volume = Σ qty`); VWAP and imbalance become `double` only at the final
`Candle`. **Trade-flow imbalance** is `(buyVolume − sellVolume) / totalVolume` over the window's prints
— aggressor pressure from the trade tape (not order-book depth imbalance, which needs L2 data the public
feed doesn't give us).

`TradeAnalytics.candles(...)` expresses the topology independent of source and sink, so the *same*
pipeline runs against Kafka in production and against an in-memory list in tests.

## 5. What was verified here, and how

Docker wasn't available in the authoring environment, so the Kafka end-to-end is run by you. What is
covered by automated tests (`./gradlew :streaming:test`, no Docker):

- **`WindowStatsAccumulatorTest`** — OHLC, volume, VWAP, buy/sell volume, imbalance, and merge.
- **`TradeEventJsonAndForwarderTest`** — JSON round-trip, and that `AsyncTradeForwarder` delivers
  engine trades to the sink on a *different* thread.
- **`TradeAnalyticsFlinkTest`** — runs the **real Flink pipeline on an embedded MiniCluster** over a
  bounded in-memory source (a bounded source emits a final watermark, firing all windows) and asserts
  the resulting candles. This proves the actual `keyBy/window/aggregate` topology — not just the
  maths — works on Java 21.

```
window [0,10000):  O=100 H=102 L=98 C=98  vol=6  buyVol=3 sellVol=3  vwap=100.67
window [10000,20000): O=105 ... vol=5 imbalance=+1.000 (all buy)
```

> **Java 17+ note.** Flink reflects into JDK internals, so the module passes a set of `--add-opens`
> flags (see `streaming/build.gradle.kts`). We also resolved a dependency *capability* clash: Flink
> bundles `org.lz4:lz4-java` while `kafka-clients` 4.x uses the `at.yawk.lz4` fork — both claim the
> `lz4-java` capability, resolved to the highest version. Expect this kind of integration tax when
> combining big data frameworks.

---

## Key takeaways

1. The streaming tier lives **outside** the LL core; `AsyncTradeForwarder` is the seam that keeps Kafka
   and Flink off the engine thread (drop-on-overflow backpressure, not stall).
2. **Kafka** is the networked async fan-out; **Chronicle** (Chunk 5) is the in-process recovery
   journal — complementary, not competing.
3. **Event-time windowing + watermarks** make analytics correct and reproducible over delayed/replayed
   data; the watermark is the completeness-vs-latency dial.
4. Use **incremental aggregation** (`AggregateFunction` + `ProcessWindowFunction`) for O(1) window
   state; keep the maths in plain testable Java.
5. Combining frameworks brings **integration tax** (JVM module flags, dependency capability clashes) —
   normal, and worth budgeting for.

## Next: Chunk 7 — benchmarking, tuning & observability

We return to the core and pull the whole LL toolkit together: a full latency benchmark suite
(p50/p99/p99.9/p99.99), GC experiments (ZGC vs Shenandoah vs Epsilon), thread affinity / CPU pinning,
and flame graphs — taming the tail latency that the Chunk 3 demo left noisy.
