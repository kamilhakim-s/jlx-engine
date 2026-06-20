# Chunk 2 — Zero-Allocation & Data-Structure Optimisation

> **Running documentation.** Chunk 2 keeps the Chunk 1 engine as a readable reference and builds a
> second engine with the *same behaviour* but a *zero-allocation* hot path: primitive-keyed
> collections, an intrusive linked list, object pools, and a reused trade flyweight. We prove the
> behaviour is identical (a randomised equivalence test) and measure the allocation difference with
> JMH + the GC profiler. The numbers teach a subtler lesson than "fewer allocations = faster" — read
> on.

---

## What we built

```
engine-core/src/main/java/com/lowlatency/engine/
├─ ObjectPool.java          single-threaded free-list (acquire/release, pre-filled)
├─ PriceLevel.java          intrusive FIFO list of orders (links live in Order.next/prev)
├─ MutableTrade.java        one reused Trade instance (flyweight)
├─ FastOrderBook.java       fastutil Long2ObjectRBTreeMap levels + Agrona Long2ObjectHashMap ids
└─ FastMatchingEngine.java  same algorithm as MatchingEngine, allocation-free match path

engine-core/src/test/java/com/lowlatency/engine/
├─ EngineEquivalenceTest.java   50k randomised ops: fast engine ≡ reference engine
└─ FastMatchingEngineTest.java  flyweight-reuse and pool-recycling assertions

benchmarks/src/jmh/java/com/lowlatency/bench/
└─ MatchingBenchmark.java   Chunk 1 vs Chunk 2, two scenarios, with the GC profiler
```

Run it:
```bash
./gradlew :engine-core:test                                  # incl. the equivalence test
./gradlew :benchmarks:jmh -Pjmh.includes=MatchingBenchmark   # before/after, with B/op
```

---

## 1. Why allocation is the enemy in low latency

Allocating an object in the JVM is cheap (a pointer bump in a thread-local allocation buffer, TLAB).
The cost comes *later*: every object you allocate is an object the **garbage collector** must
eventually trace and reclaim, and a GC cycle can **pause** your engine. A pause is a latency spike —
exactly the tail-latency poison Chunk 0 taught us to fear. So the low-latency rule is blunt: **don't
allocate on the hot path.** No garbage ⇒ no GC ⇒ no GC pauses.

This reframes the goal. We are not chasing a smaller average latency (allocation is too cheap for
that in a microbenchmark); we are removing the *source of pauses* that wreck the p99.9.

## 2. What Chunk 1 allocated, and the four fixes

| Chunk 1 source of garbage | Chunk 2 fix | File |
|---|---|---|
| `TreeMap<Long,…>` boxes a `Long` per price; tree nodes per insert | **fastutil `Long2ObjectRBTreeMap`** — primitive `long` keys, no boxing | `FastOrderBook` |
| `HashMap<Long,Order>` boxes ids | **Agrona `Long2ObjectHashMap`** — primitive `long` keys | `FastOrderBook` |
| `ArrayDeque` node per resting order | **intrusive list** — links live in `Order.next/prev`, the order *is* the node | `PriceLevel` |
| `new TradeRecord` per trade; `new Order` per message | **object pools** + a reused **`MutableTrade`** flyweight | `ObjectPool`, `MutableTrade`, `FastMatchingEngine` |

Two patterns are worth naming because you'll use them everywhere in LL Java:

- **Flyweight passed to a callback.** The engine fills one `MutableTrade` and hands the *same*
  reference to `onTrade` for every trade (`FastMatchingEngineTest.everyTradeIsTheSameReusedInstance`
  asserts the identity). The contract: the callee must copy fields out if it wants to keep them; it
  must not retain the reference. Zero allocation per trade.
- **Object pool + ownership.** Callers draw an `Order` from `engine.orderPool()`, fill it, submit it;
  the engine *owns* it thereafter and recycles it on full fill or cancel, or keeps it while it rests
  (`fullyFilledOrdersAreReturnedToThePool` asserts the pool returns to its starting size).

## 3. The zero-allocation match path

The hot path — consuming existing liquidity — reads the touch with **`firstLongKey()` + `get(long)`**
(`FastMatchingEngine.match`). No iterator object, no boxed key, no per-trade record. Compare Chunk 1,
which walks `entrySet().iterator()` and `new`s a `TradeRecord`. (Resting a brand-new price level still
allocates one node *inside* fastutil's tree, but that's off the steady-state match path — in real
trading, price levels persist across many messages.)

## 4. Proving behaviour didn't change

Rewriting the data structures is risky; the safety net is
[`EngineEquivalenceTest`](../engine-core/src/test/java/com/lowlatency/engine/EngineEquivalenceTest.java):
it drives **50,000 randomised orders and cancels** (fixed seed) through *both* engines and asserts an
identical trade sequence (same fills, same order, same sequence numbers) **and** an identical final
book. Plus the whole Chunk 1 `MatchingEngineTest` still passes. Optimise fearlessly when a test like
this has your back.

## 5. The measurements — and the real lesson

`MatchingBenchmark` runs two scenarios. Representative results (Apple Silicon, JDK 21; your absolute
numbers will differ — the *shape* is the point):

```
Benchmark                              Score (ns/op)   ·gc.alloc.rate.norm (B/op)   gc.count
chunk1_matchOnly                            5.33                ≈ 0                    ≈ 0
chunk2_matchOnly                            7.68                ≈ 0                    ≈ 0
chunk1_restAndSweep                        24.70              233.4                    167   (≈9 GB/s, 67ms GC)
chunk2_restAndSweep                        45.77               18.8                     12   (≈0.4 GB/s, 5ms GC)
```

### Lesson A — *escape analysis can make naive code allocation-free* (the `matchOnly` rows)
When a tiny aggressive order fully fills against one deep resting order, **both** engines measure ~0
B/op — and Chunk 1 is *faster*. Why? The JVM's **escape analysis** proves the `TradeRecord` and the
iterator never leave the inlined call, **scalar-replaces** them, and allocates nothing. The takeaway
is not "optimisation was pointless" — it's **measure, don't assume**. The naive engine's zero-ness is
*fragile*: it depends on aggressive inlining and a monomorphic call site, and it evaporates the moment
an object genuinely escapes (next lesson) or the call site goes megamorphic.

### Lesson B — *when objects escape, pooling delivers a real, EA-proof win* (the `restAndSweep` rows)
Here each op rests 16 orders **into the book's long-lived collections** (they escape — EA can't touch
them) and sweeps them. Chunk 1 now allocates **233 B/op** and churns **~9 GB/s** of garbage, forcing
**167 GC cycles** (67 ms of GC) during the run. Chunk 2 drops to **18.8 B/op**, **~0.4 GB/s**, and
**12 GC cycles** (5 ms). That's a ~12× allocation cut and ~13× less GC work — and it does **not**
depend on the JIT being clever.

### Lesson C — *the payoff is GC pauses, not average ns/op*
Notice Chunk 2 is *slower* on average in `restAndSweep` (45.8 vs 24.7 ns/op): pooling and the two
primitive maps have real fixed overhead, and in a warm single-threaded microbenchmark TLAB allocation
is so cheap that Chunk 1's garbage is "free" — until the GC runs. The honest framing: **zero
allocation buys you fewer and shorter GC pauses (167 → 12 collections here), which is a tail-latency
and determinism win, not an average-latency win.** Tie this straight back to Chunk 0: the average
lies; the tail (and the pauses that cause it) is what we're engineering against. The full benefit of
Chunk 2 lands in Chunk 3 (single-writer engine under sustained load) and Chunk 7 (GC tuning &
percentile measurement), where pauses dominate the p99.9.

## 6. Reading allocation yourself (JFR / async-profiler)

The GC profiler gives a number; to see *where* garbage comes from, profile allocations:
```bash
# JFR: record an allocation profile of the churn benchmark JVM and list the top allocators
./gradlew :benchmarks:jmh -Pjmh.includes=chunk1_restAndSweep \
  -Pjmh.jvmArgs="-XX:StartFlightRecording=settings=profile,filename=alloc.jfr,dumponexit=true"
jfr print --events jdk.ObjectAllocationSample alloc.jfr | head -40
```
You'll see `TreeMap$Entry`, `Long`, `ArrayDeque`, and `TradeRecord` for Chunk 1 — and essentially
nothing for the same path in Chunk 2. (async-profiler's `-e alloc` flame graph is the nicer view; we
set it up properly in Chunk 7.)

---

## Key takeaways

1. Allocation is cheap to do and expensive to clean up — **GC pauses are latency spikes**. The hot
   path should allocate nothing.
2. Four standard fixes: **primitive-keyed collections** (no boxing), **intrusive lists** (no node
   objects), **object pools** (reuse, with clear ownership), **reused flyweights** (no per-event
   objects).
3. **Escape analysis** can make naive code allocation-free in a microbenchmark — so always measure;
   never assume the allocation is real *or* that it's gone.
4. The zero-allocation win is **GC pressure and tail latency** (167 → 12 collections here), not lower
   average ns/op in a warm single-threaded benchmark.
5. A **randomised equivalence test** against the readable reference engine is what makes aggressive
   optimisation safe.

## Next: Chunk 3 — the Disruptor (single-writer engine)

We wrap this allocation-free core in an **LMAX Disruptor** ring buffer: one writer thread, mechanical
sympathy, wait strategies, and `@Contended` to avoid false sharing — then measure end-to-end latency
percentiles under sustained load, where Chunk 2's lack of GC pauses finally shows up in the tail.
