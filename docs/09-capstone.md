# Chunk 9 — Capstone: the Full Pipeline, End to End

> **Running documentation.** Every chunk so far built one organ. Chunk 9 assembles them into one living
> system and runs it: **market data → Aeron gateway → matching engine → (parallel) journal + analytics**,
> measured end-to-end, in a single command, with **no external infrastructure** — no network, no Docker.
> The value of this chunk isn't new machinery; it's seeing the seams hold when everything runs at once,
> and proving the whole assembled system is deterministic and recoverable.

---

## What we built

```
app/src/main/java/com/lowlatency/app/
├─ SyntheticMarketData.java        infra-free source of AggTrades (the Chunk 4 shape; random walk, seeded)
├─ AggTradeOverAeron.java          sends each trade over Aeron as a maker/taker pair (Chunk 4 reconstruction → the wire)
├─ WindowedCandleAggregator.java   in-process event-time candle windows (Chunk 6 maths, no Flink)
└─ CapstoneApp.java                wires every module together, measures, and prints the pipeline result
app/src/test/java/com/lowlatency/app/
└─ CapstonePipelineTest.java       full pipeline on an embedded driver + journal-replay determinism check
```

Run it:
```bash
./gradlew :app:run     # the whole pipeline, end-to-end, infra-free
./gradlew :app:test    # full pipeline on an embedded Aeron driver + deterministic-replay assertion
```

---

## 1. The assembled architecture

```
 synthetic market data ──▶ Aeron gateway ──▶ matching engine ──┬─▶ Chronicle journal   (Chunk 5, parallel consumer)
   (Chunk 4 shape)          (Chunk 8)         (Chunks 1-3)      │
        on the wire          single writer     lock-free        ├─▶ end-to-end latency  (Chunks 0/7, HdrHistogram + GC/alloc)
                                                                │
                                                                └─▶ AsyncTradeForwarder ──▶ windowed candles
                                                                     (Chunk 6 seam)          (Chunk 6 maths, in-process)
```

Each arrow is a boundary a previous chunk taught us to respect, and the capstone's job is to show they
compose. Reading left to right:

- **Source → wire.** [`SyntheticMarketData`](../app/src/main/java/com/lowlatency/app/SyntheticMarketData.java)
  emits the same normalised `AggTrade` shape Chunk 4 produces from real Binance data — seeded, so a run is
  reproducible. [`AggTradeOverAeron`](../app/src/main/java/com/lowlatency/app/AggTradeOverAeron.java)
  reconstructs each public print as a crossing maker/taker pair (Chunk 4's trick) and sends it **over
  Aeron**, exactly as a remote order source would. Swap this one class for `BinanceLiveClient` and the
  pipeline consumes the real market — nothing downstream changes.
- **Gateway → engine.** The Chunk 8 gateway decodes off the wire and is the **single writer** into the
  ring buffer, so the lock-free engine is untouched.
- **Engine → two parallel consumers.** The engine runs the matcher **and** the Chunk 5
  `CommandJournaller` as parallel Disruptor consumers of the same sequence — every command is persisted
  for replay *while* matching, off the critical path.
- **Engine → analytics seam.** Trades leave the hot path through Chunk 6's `AsyncTradeForwarder` (snapshot
  to a `TradeEvent`, hand to a bounded queue, drain on another thread) into an in-process windowed candle
  aggregator.

The point is that **no prior code changed.** The capstone is wiring, not rework — the sign that the
abstractions from Chunks 1–8 were the right shape.

## 2. Two parallel consumers, one command stream

The engine is built with the full `DisruptorMatchingService` constructor, handing it both a journaller and
a trade listener:

```java
DisruptorMatchingService engine = new DisruptorMatchingService(
        RING_SIZE, ProducerType.SINGLE, new BusySpinWaitStrategy(),
        RING_SIZE, latency, journaller, forwarder);
```

The journaller and the matcher are **independent consumers of the same ring buffer** (the LMAX pattern
from Chunk 3/5): both see every `OrderCommand` in the identical order, in parallel, neither blocking the
other. That ordering guarantee is precisely what makes the journal a faithful, replayable record — and
the capstone test cashes that in (§4).

## 3. The analytics tier, in process — the Chunk 6 lesson made literal

[`WindowedCandleAggregator`](../app/src/main/java/com/lowlatency/app/WindowedCandleAggregator.java) reuses
Chunk 6's **pure** `WindowStatsAccumulator` to fold trades into tumbling event-time windows and emit OHLC
+ VWAP + volume + imbalance candles. What it deliberately does *not* do is boot Kafka and Flink.

This is the Chunk 6 architecture stated in code: the streaming tier lives **outside** the low-latency
core, behind the `AsyncTradeForwarder` seam. The build makes it literal — the `app` module depends on
`:streaming` but **excludes** Flink and Kafka:

```kotlin
implementation(project(":streaming")) {
    exclude(group = "org.apache.flink")
    exclude(group = "org.apache.kafka")
}
```

so the capstone gets the analytics *maths* (plain Java) without dragging ~100 MB of framework — or
re-triggering Chunk 6's lz4 capability clash — onto its classpath. In production this same seam feeds
Kafka → Flink (Chunk 6's `EngineToKafkaApp` / `TradeAnalyticsJob`), which add watermarks, out-of-order
tolerance, checkpointing and scale-out. Here a single forwarder thread delivers trades in order, so
"the window advanced, emit it" needs no watermark. **The boundary is the lesson, not the framework.**

## 4. Determinism is the payoff — and it's tested

The capstone test runs the whole pipeline over an embedded Aeron driver and then does the thing that ties
the project together: it **replays the journal alone into a fresh engine** and asserts the rebuilt matched
quantity equals the live run's.

```java
FastMatchingEngine rebuilt = new FastMatchingEngine(
        new FastOrderBook(), trade -> replayedQty[0] += trade.quantity(), 1 << 16);
long replayedCommands = new JournalReplayer().replay(journalDir, rebuilt);

assertThat(replayedCommands).isEqualTo(2L * TRADES);
assertThat(replayedQty[0]).isEqualTo(liveMatchedQty);   // same input ⇒ same output
```

It also asserts the tiers **agree at the seams**: `engine.tradeCount() == TRADES`, the journaller saw
`2 × TRADES` commands (maker + taker each), the forwarder dropped nothing, and the analytics tier's total
volume equals the engine's matched quantity. Same data in, same trades out, every tier consistent — across
the full assembled system, not just a unit.

## 5. What the run shows

A representative `./gradlew :app:run` (Apple Silicon, JDK 21, IPC, 500,000 measured trades at 250k/s —
**your numbers will differ; the shape is the lesson**):

```
  engine:    trades=600,000  matched qty=3,297,623  (achieved 249,998 trades/s)
  journal:   1,200,000 commands persisted (Chronicle Queue → deterministic replay)
  analytics: 5 candles  total volume=3,297,623  (forwarder drops=0)
  transport: publisher backpressure retries=1
  latency (client → Aeron → gateway → match):
    p50=1.13 µs  p99=8.75 µs  p99.9=895.49 µs  p99.99=1147.90 µs  max=3237.89 µs
  gc/alloc over measured window: 1 collections, 3 ms total pause [G1 Young Generation ×1/3ms], 58.4 MB allocated
  cross-tier check: analytics volume == engine matched quantity
```

Read against the bare gateway of Chunk 8 (p50 ≈ 0.21 µs), the median here is higher (~1.1 µs) and we now
see a real **GC pause** — and that's the most honest, instructive part of the whole project:

- **The core is still allocation-free; the analytics seam allocates by design.** The 58 MB and the single
  G1 young-gen collection come from the `AsyncTradeForwarder` snapshotting one `TradeEvent` per trade —
  the explicitly acknowledged price of *leaving* the zero-GC core (Chunk 6). The matching engine itself
  still produces no per-order garbage; the allocation is past the seam, exactly where we decided it was
  allowed to be. This is the Chunk 7 observability earning its keep: the GC line tells you *which tier*
  paid.
- **The tail widened** because there's simply more going on per trade now — two parallel consumers (one of
  them writing to a memory-mapped journal), the forwarder offering to a queue, all on an unisolated
  laptop. The Chunk 7 verdict applies: trust the medians and the GC/alloc columns here; the tail tightens
  on a tuned, isolated box.

Everything stays consistent under load: zero drops, the analytics volume matches the engine to the unit,
and every source trade produced exactly one engine trade. The seams hold.

## 6. Running against the real market

The capstone is synthetic so it runs anywhere, but it's one swap from real:

- **Real data:** replace `SyntheticMarketData` with Chunk 4's `BinanceHistoricalDownloader.stream(...)`
  (bulk history) or `BinanceLiveClient` (live `@aggTrade`), feeding the same `AggTradeOverAeron`.
- **Real analytics:** point the `AsyncTradeForwarder` sink at Chunk 6's `KafkaTradeSink` instead of the
  in-process aggregator, and run `TradeAnalyticsJob` on Flink (needs the Chunk 6 Docker Kafka).
- **Real transport:** change the channel from `aeron:ipc` to `aeron:udp?endpoint=...` to put the gateway
  on the network.

None of those change the engine, the journal, or the seams — which was the entire point.

---

## Key takeaways

1. **The capstone is wiring, not rework.** Every module from Chunks 1–8 composed without modification —
   the proof that the boundaries (single-writer ring, gateway, journal-as-parallel-consumer, forwarder
   seam) were drawn correctly.
2. **Two parallel Disruptor consumers** (matcher + journaller) see one ordered command stream, so the run
   is journalled for replay *while* it matches — off the critical path.
3. **The analytics tier stays outside the core**, reused as plain Java with Flink/Kafka excluded from the
   classpath — Chunk 6's architecture made literal in the build file.
4. **Determinism is tested, not asserted in prose:** replaying the journal alone rebuilds the identical
   matched quantity, and every tier agrees at the seams.
5. **The core stays allocation-free; the seam allocates by design.** The capstone's one GC pause comes from
   the per-trade `TradeEvent` past the forwarder — and Chunk 7's GC/alloc observability shows exactly which
   tier paid for it. That honesty — knowing precisely where every nanosecond and every byte goes — is the
   discipline this whole project was about.

---

## The project, end to end

| Chunk | What it added | Key idea |
|---|---|---|
| 0 | Measurement harness | Measure before optimising; the tail is the truth; coordinated omission |
| 1 | Order book + matching | Correct, single-threaded price-time priority first |
| 2 | Zero-allocation engine | Object pools + primitive collections ⇒ no per-order garbage |
| 3 | Disruptor | Single-writer ring buffer; wait strategies as the latency/CPU dial |
| 4 | Market data | Real Binance history + live stream normalised into the engine |
| 5 | Journaling + replay | Chronicle Queue event-sourcing ⇒ deterministic recovery |
| 6 | Streaming analytics | Kafka/Flink **outside** the core, behind an async seam |
| 7 | Benchmarking + tuning | GC experiments, CPU affinity, reading the tail honestly |
| 8 | Aeron gateway | Orders over the wire; the engine unchanged behind the gateway |
| 9 | Capstone | It all composes — measured, consistent, and deterministic |

From a Spring Boot "add a pod" mindset to a single thread that matches millions of orders a second with a
median in the nanoseconds, journalled for recovery and fanned out to analytics — built the way a
low-latency engineer builds it: **measure first, make it correct, then make it fast, and always know where
every nanosecond goes.**
