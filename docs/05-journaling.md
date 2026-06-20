# Chunk 5 — Journaling & Deterministic Replay

> **Running documentation.** Chunk 5 makes the engine durable. We persist the inbound **command**
> stream to a Chronicle Queue as a parallel Disruptor consumer, then rebuild engine state after a
> crash by **deterministically replaying** the journal — and prove the replayed trade tape is
> byte-for-byte identical to the live one. This is the event-sourcing model real exchanges run on.

---

## What we built

```
journal/src/main/java/com/lowlatency/journal/
├─ CommandCodec.java        fixed-schema (de)serialisation of an OrderCommand ↔ Chronicle Wire
├─ CommandJournaller.java   EventHandler<OrderCommand> → appends each command to a Chronicle Queue
├─ JournalReplayer.java     reads the queue, re-applies every command to a fresh engine
├─ SyntheticOrderFlow.java  deterministic order/cancel generator (fixed seed)
└─ JournalDemo.java         live session → "crash" → replay → verify identical

engine-disruptor (extended): DisruptorMatchingService now accepts an optional parallel journaller
                             consumer and a trade listener.
```

Run it:
```bash
./gradlew :journal:test    # round-trip determinism + codec + cancel survival
./gradlew :journal:run     # the full crash-recovery demo (output below)
```

---

## 1. Why journal *commands*, not *state*

A matching engine holds a large, fast-changing book in memory. How do you make it survive a crash
without snapshotting that whole book on every order (far too slow)? The exchange answer is **event
sourcing**: the durable source of truth is the **ordered stream of input commands**, not the derived
state. If you persist every command, you can rebuild the exact state at any time by replaying them —
*provided the engine is deterministic*, which ours is (single-writer, integer maths, no wall-clock or
randomness in the matching logic).

So we journal **`NEW_ORDER` / `CANCEL` commands**, each tiny and fixed-size, and treat the book as a
pure function of that stream. Recovery = replay.

## 2. Journal-then-process, in parallel — the LMAX shape

Where does journaling sit relative to matching? Naively you'd write to disk *then* match — but that
puts disk latency on the critical path. The LMAX design instead runs the journaller as a **second,
independent consumer of the same ring buffer**:

```
                       ┌────────────► CommandJournaller ──► Chronicle Queue (disk)
publishers ─► RingBuffer
                       └────────────► MatchingEngineEventHandler ──► engine
```

Both consumers see **every command in the identical ring sequence**. We wired this in
`DisruptorMatchingService` with `handleEventsWith(journaller, matcher)` — the two run concurrently on
their own threads, so journaling latency overlaps with (rather than blocks) matching. And because they
consume the same totally-ordered sequence, the journal records *exactly* what the engine processed, in
*exactly* the order it processed it. That shared order is the linchpin of deterministic replay.

(For strict durability you can make the engine wait for the journaller's sequence before publishing a
trade outward — "journal before you acknowledge". We keep them fully parallel here for clarity; the
ordering guarantee is the part that matters for replay.)

## 3. Chronicle Queue — a memory-mapped persisted log

[`CommandJournaller`](../journal/src/main/java/com/lowlatency/journal/CommandJournaller.java) appends
to a **Chronicle Queue**: an off-heap, **memory-mapped** persisted queue (a directory of roll-files).
An append is a write into a mapped file region — no per-record `write()` syscall, no on-heap
serialisation buffer, no allocation — so it can keep pace with the engine. The
[`CommandCodec`](../journal/src/main/java/com/lowlatency/journal/CommandCodec.java) writes a **fixed
six-field schema** (type, id, side, orderType, price, quantity) so reads are simple and robust; for a
`CANCEL` the order-specific fields are written as neutral sentinels rather than the reused event's
stale values.

> **Java 17+ note.** Chronicle reaches into JDK internals for memory mapping and cleaners, so the
> module passes a set of `--add-opens/--add-exports` flags (see `journal/build.gradle.kts`). We also
> set `-Dchronicle.analytics.disable=true` to stop its phone-home. This is normal operational tax for
> off-heap libraries on modern JDKs — worth knowing before you hit it in production.

## 4. Replay = recovery

[`JournalReplayer`](../journal/src/main/java/com/lowlatency/journal/JournalReplayer.java) opens the
queue with an `ExcerptTailer` and walks every document from the start, applying each command to a
**fresh** `FastMatchingEngine` with the *identical* logic the live consumer used (`CommandCodec.apply`
mirrors `MatchingEngineEventHandler`). Same inputs + same order + deterministic engine ⇒ same state and
same trades. That's the whole recovery story: on restart, you replay the journal and you're back
exactly where you crashed.

## 5. Proving determinism

[`JournalDemo`](../journal/src/main/java/com/lowlatency/journal/JournalDemo.java) feeds 200,000
deterministic commands through the live engine while journaling in parallel and captures the **live
trade tape**; then it constructs a brand-new engine, replays the journal, and captures the **replayed
tape**. Real output:

```
live    : journalled=200,000  engineTrades=137,091  matchedUnits=416,323
recover : applied=200,000  replayedTrades=137,091
DETERMINISTIC ✓  the replayed trade tape is byte-for-byte identical to the live tape
```

`JournalRoundTripTest` asserts the same property automatically (`replayTape.equals(liveTape)`), plus a
no-Disruptor codec round-trip and a test that **cancels survive replay** (a maker cancelled before its
taker arrives produces no trade on replay, just as live). Identical tapes — including the engine's
monotonic trade sequence numbers — mean identical state was re-derived from nothing but the journal.

## 6. Chronicle Queue vs Kafka (why this, here)

Both are append-only logs, but they sit at different tiers:

| | Chronicle Queue (this chunk) | Kafka (Chunk 6) |
|---|---|---|
| Where | in-process, same host, memory-mapped | networked broker cluster |
| Latency | sub-microsecond append | milliseconds |
| Role here | hot-path **journal** for crash recovery | async **fan-out** to the analytics tier |

Journaling for recovery wants the lowest possible append latency on the same box → Chronicle. Shipping
the trade stream to consumers that don't share the engine's latency budget wants durability and
network fan-out → Kafka, which is exactly Chunk 6.

---

## Key takeaways

1. **Event sourcing**: persist the ordered *command* stream, treat state as a replayable function of
   it. A deterministic engine is the precondition.
2. **Journal in parallel**, as a second consumer of the same ring buffer — same total order as the
   matcher, off the matching critical path.
3. **Chronicle Queue** is a memory-mapped, off-heap, allocation-free persisted log — fast enough for
   the hot path (with some JDK module flags as tax).
4. **Replay = recovery**: same inputs + same order + deterministic engine ⇒ byte-identical state and
   trades, which we verified on 200k commands.
5. Chronicle (in-process recovery journal) and Kafka (networked analytics fan-out) are
   complementary, not competitors.

## Next: Chunk 6 — streaming analytics (Kafka + Flink)

We publish the engine's trade output to Kafka — off the hot path — and compute live VWAP, OHLC candles,
and order-book imbalance in Flink with event-time windows. The streaming tier lives *outside* the
latency-critical core, which is precisely why Kafka belongs there and Chronicle belongs here.
