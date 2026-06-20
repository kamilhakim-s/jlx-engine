# Chunk 3 — The Disruptor: a Single-Writer Engine

> **Running documentation.** Chunk 3 wraps the allocation-free engine from Chunk 2 in an **LMAX
> Disruptor** ring buffer. The engine stays single-threaded and lock-free; we make it safe under
> concurrent input by guaranteeing exactly **one writer thread** ever touches it. Along the way we meet
> ring buffers, wait strategies, cache lines, and false sharing — and we measure end-to-end latency
> percentiles.

---

## What we built

```
engine-disruptor/src/main/java/com/lowlatency/engine/disruptor/
├─ CommandType.java                  NEW_ORDER | CANCEL
├─ OrderCommand.java                 the mutable ring-buffer event (pre-allocated, reused)
├─ CountingTradeSink.java            TradeHandler that tallies trades on the consumer thread
├─ MatchingEngineEventHandler.java   the single consumer = the single writer to the engine
├─ DisruptorMatchingService.java     wires the ring buffer + publish API + lifecycle
└─ DisruptorLatencyDemo.java         end-to-end latency under 3 wait strategies

engine-disruptor/src/test/java/.../DisruptorMatchingServiceTest.java
benchmarks/src/jmh/java/.../FalseSharingBenchmark.java
```

Run it:
```bash
./gradlew :engine-disruptor:test                              # correctness through the async path
./gradlew :engine-disruptor:run                               # latency percentiles per wait strategy
./gradlew :benchmarks:jmh -Pjmh.includes=FalseSharingBenchmark
```

---

## 1. The problem: concurrency without locks

Our matching engine is fast precisely because it's single-threaded — no locks, no atomics, no memory
fences on the hot path. But an exchange has many order sources hitting it at once. The instinctive fix
— wrap the engine in a `synchronized` block or feed it from an `ArrayBlockingQueue` — reintroduces
exactly the costs we avoided: lock contention, cache-line ping-pong on the lock word, and allocation
of queue nodes. Under load those produce the GC- and contention-driven tail spikes Chunk 0 warned
about.

The LMAX insight (from building a financial exchange that did 6M orders/sec on one thread): **the
fastest way to share mutable state is to not share it.** Keep the engine single-threaded; make the
*hand-off* to it lock-free.

## 2. The single-writer principle

In [`MatchingEngineEventHandler`](../engine-disruptor/src/main/java/com/lowlatency/engine/disruptor/MatchingEngineEventHandler.java)
the engine is only ever touched by **one thread** — the Disruptor's consumer. Producers never call the
engine; they only publish `OrderCommand`s into the ring buffer. So:

- the engine needs **no synchronisation** — there is no second writer to race with;
- because we're on the engine's only thread, we can freely use its `ObjectPool` (Chunk 2);
- ordering is total and deterministic — commands apply in ring-buffer sequence, which is exactly what
  Chunk 5's deterministic replay will rely on.

`DisruptorMatchingServiceTest.manyProducersAreSerialisedOntoTheSingleWriter` fires **4 producer
threads × 25,000 orders** at one deep resting order and asserts every single buy matched exactly one
unit — no lost updates, no corruption — despite the concurrency. The ring buffer serialised them; the
engine never knew there was more than one producer.

## 3. The ring buffer — mechanical sympathy in a data structure

A Disruptor ring buffer is a **fixed-size array** (power-of-two length) of **pre-allocated** event
objects, plus two monotonically increasing `long` **sequence** counters: the publisher cursor and the
consumer sequence. Publishing is claim-then-commit:

```java
long seq = ringBuffer.next();              // claim the next slot (spins if the consumer is behind)
try { ringBuffer.get(seq).setNewOrder(…); } // overwrite the pre-allocated event in place
finally { ringBuffer.publish(seq); }        // commit — now visible to the consumer
```

Why this is fast, point by point — each is a "mechanical sympathy" win:

- **No allocation.** Events are created once at start-up ([`OrderCommand::new`]) and reused forever.
  Compare a linked-queue, which allocates a node per message (and scatters them across the heap).
- **No locks.** Coordination is via CAS/lazy-set on the sequence counters, not mutexes.
- **Cache-friendly.** A contiguous array with sequential access is exactly what the CPU prefetcher
  loves; a power-of-two size turns index maths into a bitmask (`seq & (size-1)`).
- **Mechanical batching.** When the consumer wakes it drains *all* currently-published events in a
  batch, amortising wake-up cost — no per-item signalling.

`RING_SIZE` also bounds memory and provides natural **backpressure**: if the consumer falls behind,
`next()` spins until a slot frees, rather than letting an unbounded queue grow (the failure mode of
`LinkedBlockingQueue`).

## 4. Wait strategies — the latency/CPU dial

How should the consumer wait when the ring is empty? That single choice is the biggest latency knob in
the system. Measured end-to-end (publish → matched), 1,000,000 orders at 500k/s (Apple Silicon, JDK
21 — your numbers will differ; the *shape* is the lesson):

```
strategy     p50       p99       p99.9     p99.99    max        cost
BusySpin     0.08 µs   0.17 µs   6.13 µs   48.4 µs   153.7 µs   one core pinned at 100%
Yielding     0.13 µs   1.88 µs   20.8 µs   58.7 µs   110.9 µs   spins then Thread.yield()
Blocking     2.46 µs   7.25 µs   10.7 µs   14.9 µs   51.8 µs    lock+condition, ~0 idle CPU
```

Read it carefully — it's a genuine trade-off, not a ranking:

- **BusySpin** gives a stunning **80 ns median**: the consumer never sleeps, so a published event is
  seen almost immediately. The price is a CPU core burned at 100% even when idle.
- **Blocking** parks the consumer on a lock/condition: near-zero idle CPU, but every message pays a
  thread wake-up (~2.5 µs median here).
- **Yielding** sits between: spin for a while, then yield the core.

Note the **tails** are noisier than the medians and don't strictly order — on a normal laptop the OS
preempts our threads, NUMA effects bite, and there's no CPU isolation. That's expected, and it's the
cue for **Chunk 7**, where thread affinity / CPU pinning and GC tuning tighten these tails. The rule of
thumb LL shops use: **BusySpin (or a custom spin) on isolated cores in production; Blocking in
dev/CI** where you don't want a pegged core.

## 5. Cache lines and false sharing

Why does the Disruptor pad its sequence counters? Because of **false sharing**. CPUs move memory in
**cache lines** (~64 bytes), not single variables. If two threads write two *different* fields that
land on the *same* line, each write invalidates the other core's copy and forces a re-fetch — the
fields are independent, but the cores ping-pong the line anyway.

[`FalseSharingBenchmark`](../benchmarks/src/jmh/java/com/lowlatency/bench/FalseSharingBenchmark.java)
runs two threads incrementing two counters. Adjacent (same line) vs separated by 7 longs of padding
(different lines):

```
FalseSharingBenchmark.adjacent   340,408 ops/ms
FalseSharingBenchmark.padded   1,105,245 ops/ms     ← ~3.2× faster, identical logic
```

Same code; the only difference is memory layout. The fixes:

- **Manual padding** — the classic `long p1..p7` between hot fields (what the benchmark does, and what
  Disruptor did historically).
- **`@jdk.internal.vm.annotation.Contended`** — tells the JVM to pad a field/class onto its own line.
  It's a restricted annotation: application code needs `-XX:-RestrictContended` (and an `--add-exports`)
  to use it, which is why libraries like Disruptor and Agrona pad by hand or use their own annotation.
  The Disruptor's `Sequence` is cache-line padded internally, so the publisher cursor and consumer
  sequence never falsely share — you get this for free by using it.

## 6. Honest measurement (carried over from Chunk 0)

The demo deliberately applies the Chunk 0 discipline so the numbers above are trustworthy:
**warm up** untimed first (let the JIT compile the hot path), **pace** the producer to a fixed rate and
stamp each command with its *intended* send time so latency is measured from when it was *due*
(coordinated-omission-safe), and report **percentiles**, never the mean. The latency is recorded by the
consumer in an `HdrHistogram` as `now − ingressNanos`.

---

## Key takeaways

1. **Single-writer principle**: don't lock shared state — arrange for only one thread to touch it. The
   ring buffer serialises many producers onto one consumer; the engine stays lock-free.
2. A **ring buffer** wins by being a pre-allocated, contiguous, power-of-two array coordinated by
   sequence counters — no allocation, no locks, cache-friendly, naturally back-pressured.
3. **Wait strategy** is the main latency/CPU dial: BusySpin for lowest latency (burns a core),
   Blocking for lowest CPU (higher median), Yielding in between.
4. **False sharing** can silently cost ~3× throughput; fix it with padding / `@Contended`. The
   Disruptor pads its sequences for you.
5. Tail latency on an untuned machine is noisy — **Chunk 7** (CPU pinning, GC tuning) is where we tame
   it.

## Next: Chunk 4 — real market data from Binance

We feed the engine real data: a REST downloader for historical trades (the large-dataset pull) and a
WebSocket client for the live stream, normalised into `OrderCommand`s and published into this exact
ring buffer — so the engine we just built starts matching against the real market.
