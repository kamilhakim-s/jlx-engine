# Chunk 7 — Benchmarking, Tuning & Observability

> **Running documentation.** Chunk 7 returns to the low-latency core and pulls the whole toolkit
> together. We build a *reusable* end-to-end latency benchmark with GC and allocation observability baked
> in, run the **same workload under different garbage collectors** (G1 / ZGC / Parallel / Epsilon) to see
> how the collector shapes the tail, demonstrate **CPU affinity**, and — most importantly — learn to
> *read* the tail-latency noise the Chunk 3 demo left us with. This is the chunk where measurement,
> which we built first back in Chunk 0, finally becomes a tuning loop.

---

## What we built

```
tuning/src/main/java/com/lowlatency/tuning/
├─ GcStats.java              GC collector counts + pause time over a run (java.lang.management)
├─ AllocationCounter.java    heap bytes allocated over a run (HotSpot per-thread accounting)
├─ WaitStrategies.java       name → Disruptor WaitStrategy
├─ CpuAffinity.java          best-effort core pinning (real on Linux, advisory/no-op elsewhere)
├─ BenchmarkConfig.java      run parameters (overridable via -Dbench.*)
├─ BenchmarkResult.java      percentiles + GC + allocation for one run
├─ LatencyBenchmark.java     the reusable harness: paced producer → engine → HdrHistogram + observability
└─ LatencyBenchmarkApp.java  the headline app (wait-strategy matrix, or a single GC-experiment run)
```

Run it:
```bash
./gradlew :tuning:run          # the wait-strategy matrix (busy-spin vs yielding vs blocking) under G1
./gradlew :tuning:test         # fast smoke tests (tiny workload; asserts wiring + ~0 alloc/order)

# GC experiments — the SAME workload under different collectors, directly comparable:
./gradlew :tuning:benchG1        # G1, the default server collector
./gradlew :tuning:benchZgc       # ZGC (generational), concurrent sub-ms pauses
./gradlew :tuning:benchParallel  # Parallel, the throughput collector
./gradlew :tuning:benchEpsilon   # Epsilon, the NO-OP collector — proves the hot path allocates ~nothing

# Override any knob (forwarded into the forked JVM):
./gradlew :tuning:run -Dbench.matrix=false -Dbench.waitStrategy=busyspin -Dbench.pinCpu=true
```

---

## 1. The benchmark harness — methodology is non-negotiable

[`LatencyBenchmark`](../tuning/src/main/java/com/lowlatency/tuning/LatencyBenchmark.java) generalises the
Chunk 3 `DisruptorLatencyDemo` into a reusable harness. The methodology is the Chunk 0 discipline,
unchanged because it is the difference between truth and a comfortable lie:

- **Warm up untimed first.** We push hundreds of thousands of orders with `ingressNanos = 0` (the handler
  skips recording those) so the JIT has compiled the publish → match → record path to optimised machine
  code *before* the stopwatch starts. Measuring cold code measures the interpreter, not the system.
- **Pace and stamp from the intended instant.** The producer is paced to a fixed rate, and each command
  carries its *intended* send time. Latency is `now − intended`, measured from when the order was **due**.
  If the engine stalls, the orders queued behind the stall record their full wait — this is what defeats
  **coordinated omission** (Chunk 0). Timing from "when the engine dequeued it" would hide exactly the
  tail we care about.
- **Report percentiles, never the mean** — into an `HdrHistogram`, read out as p50 / p99 / p99.9 / p99.99
  / max.

What's *new* in Chunk 7 is that the harness brackets the measured loop with observability snapshots
([`GcStats`](../tuning/src/main/java/com/lowlatency/tuning/GcStats.java) +
[`AllocationCounter`](../tuning/src/main/java/com/lowlatency/tuning/AllocationCounter.java)), so every run
reports the GC pauses and bytes allocated *over the same window as the percentiles*. When a tail spike
appears, the GC line next to it tells you whether the collector caused it.

## 2. The wait-strategy matrix — the biggest latency dial, revisited

`./gradlew :tuning:run` reruns the Chunk 3 comparison through the new harness. A representative run (Apple
Silicon, JDK 21, G1, 1,000,000 orders at 500k/s — **your numbers will differ; the *shape* is the lesson**):

```
strategy       p50       p99      p99.9     p99.99      max        GC        alloc
BusySpin     0.08µs    0.17µs    3.84µs    42.40µs   203.78µs    0/0ms      4.2KB
Yielding     0.13µs    5.71µs   54.34µs   152.58µs   206.72µs    0/0ms       392B
Blocking     2.50µs   19.55µs  372.48µs  1542.14µs  1707.01µs    0/0ms       392B
```

The medians are the stable, reproducible lesson: **BusySpin** never sleeps, so a published event is seen
in ~80 ns (it burns a core for the privilege); **Blocking** parks on a lock/condition and pays a ~2.5 µs
thread wake-up per message but idles at ~0% CPU; **Yielding** sits between. The `GC` column reads `0/0ms`
across the board — *no collections happened at all* — which sets up the next section.

## 3. The allocation story — why GC barely fires (and Epsilon survives)

Look at the `alloc` column: **~4 KB total for the entire 1,000,000-order measured loop.** That's not 4 KB
per order — it's 4 KB for *all of them*. The matching hot path, built allocation-free since Chunk 2 (the
`ObjectPool`, primitive-keyed structures, reused ring-buffer events), produces essentially zero garbage;
the few kilobytes are HdrHistogram/JIT bookkeeping, not per-order objects. Allocation is the *root cause*
of GC pauses, so measuring it directly is the leading indicator of tail latency — you don't wait for a
pause to show up in the histogram, you confirm there's nothing to collect.

The most pointed way to *prove* this is the **Epsilon GC** — HotSpot's no-op collector. It allocates from
the heap but **never reclaims**; if a workload allocates steadily, it OOMs. Run:

```bash
./gradlew :tuning:benchEpsilon   # -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xmx2g
```

It completes 1,000,000 orders without trouble. The engine never handed Epsilon enough garbage to matter —
that completion *is* the proof the matching path is allocation-free, more convincing than any alloc-rate
number. (Epsilon is also a sharp tool for catching regressions: if someone reintroduces allocation on the
hot path, the Epsilon run starts collecting-then-OOMing where it used to sail through.)

## 4. GC experiments — the same workload under four collectors

Each `bench*` task runs the identical single-strategy busy-spin workload under a different collector, so
the only variable is the GC. Representative tails from one session:

```
collector          p50      p99      p99.9      p99.99      max       collections
G1 (default)     0.08µs   0.17µs    3.84µs     42.40µs   203.78µs    0
Parallel         0.08µs   0.21µs    4.09µs     76.42µs   197.63µs    0
ZGC (gen.)       0.13µs   0.21µs  687.10µs   2424.83µs  2615.30µs    0
Epsilon (no-op)  0.13µs   0.17µs   10.75µs    971.78µs  1130.50µs    0
```

How to read this:

- **Zero collections everywhere.** Because the workload barely allocates, none of the collectors had a
  reason to run. So this is *not* a "which GC pauses less" race — we never triggered a pause. What we're
  actually seeing is each collector's **steady-state overhead**: the read/write barriers and bookkeeping
  the JIT must emit even when no collection happens.
- **ZGC's tail is worse here, and that's expected.** ZGC is a *concurrent* collector designed for very
  large heaps with sub-millisecond pauses — it earns that by inserting **load barriers** on every
  reference read, which costs a little on the hot path. For a tiny, zero-garbage workload like ours that
  overhead is all cost and no benefit. ZGC shines when you have a multi-gigabyte heap and *can't* tolerate
  a 200 ms G1 pause; it is the wrong tool for an allocation-free nanosecond path. The lesson is
  **collector choice depends on the allocation profile** — there is no universally "fastest" GC.
- **G1, Parallel, Epsilon are close** because none is doing real work here; the differences are within the
  run-to-run noise of an unisolated laptop (see §6).

> **Shenandoah** (another low-pause concurrent collector) is intentionally absent from the tasks: it ships
> in OpenJDK/Temurin builds but **not** in the Oracle JDK this project was authored on, so a `benchShenandoah`
> task would fail. On a Temurin JDK you'd add `-XX:+UseShenandoahGC` exactly like the others — same story
> as ZGC: a concurrent collector whose barriers cost on a zero-garbage path.

## 5. CPU affinity — pinning the latency-critical thread

By default the OS scheduler may migrate a thread between cores at any time. Each migration is a
tail-latency event: the new core's L1/L2 caches are cold, so the next few thousand instructions stall on
cache misses, and you may cross a NUMA boundary. The fix is **CPU affinity**: pin the latency-critical
thread to a fixed core so its working set stays hot and scheduler jitter disappears.

[`CpuAffinity`](../tuning/src/main/java/com/lowlatency/tuning/CpuAffinity.java) wraps OpenHFT
Java-Thread-Affinity in a try-with-resources handle, used by the harness when `-Dbench.pinCpu=true`:

```java
try (CpuAffinity ignored = CpuAffinity.pinToAnyCore()) {
    // the producer thread now stays on one core for the whole run
}
```

**Be honest about what this buys you, and where.** Real hard pinning only exists on **Linux**. There the
full recipe is: isolate one or more cores from the scheduler at boot (`isolcpus=...` and `nohz_full=...`
on the kernel command line so the OS evicts *everything else* — including the timer tick — from those
cores), then pin the engine's consumer thread to an isolated core. A busy-spin consumer then truly *owns*
a core and the tail tightens dramatically. On **macOS/Windows**, thread-to-core binding is only advisory
(the kernel may ignore it), so pinning on this laptop neither helps nor reliably hurts — the demo runs and
teaches the API, but the tail stays noisy because nothing is actually isolated. `CpuAffinity.isReal()`
reports whether the platform actually bound a core.

> **Two integration taxes worth remembering.** (1) The affinity library pulls a *native* dependency (JNA);
> the version it transitively pins (5.5.0) ships **no arm64 binary** and fails to load on Apple Silicon,
> so the build force-upgrades JNA to 5.14.0 (Gradle resolves the higher version) — a reminder that native
> libs and CPU architectures are a real source of "works on my machine." (2) Acquiring the lock loads the
> native library and scans the CPU topology, a one-time ~hundreds-of-ms cost; the harness acquires it
> *before* the untimed warmup so that cost is never charged to a measured sample.
>
> In production you'd also pin the **consumer** (engine) thread, not just the producer — that needs a
> custom `ThreadFactory` for the Disruptor; here we pin the producer to demonstrate the technique without
> reworking the Chunk 3 service.

## 6. Reading the tail — why a laptop lies, and what to trust

Notice how the **medians** above are rock-steady run to run (~80 ns busy-spin) while the **tails**
(p99.9+) jump around by 10× between runs. That is not the engine — it's the environment:

- the OS preempts our threads to run everything else on the machine;
- cores migrate and caches go cold (the affinity problem above);
- on a laptop there is thermal throttling, no NUMA pinning, no core isolation, and a busy desktop.

This is exactly the noise the Chunk 3 doc flagged and promised to tame "in Chunk 7." The honest answer
Chunk 7 gives is: **you don't tame a noisy tail by tuning the code — you tame it by controlling the
environment.** The same engine on a tuned Linux box (isolated cores, busy-spin consumer pinned to one of
them, `performance` CPU governor, transparent huge pages off, IRQs steered away) produces tails an order
of magnitude tighter and *stable*. The measurement discipline (warm up, pace, time-from-intended,
percentiles) makes the numbers *trustworthy*; the environment makes them *good*. Trust the medians and the
GC/alloc columns on this laptop; trust the tails only on an isolated box.

## 7. Observability hooks for going further

The harness reads GC and allocation from the JDK's own management beans
([`GcStats`](../tuning/src/main/java/com/lowlatency/tuning/GcStats.java) via
`java.lang.management.GarbageCollectorMXBean`;
[`AllocationCounter`](../tuning/src/main/java/com/lowlatency/tuning/AllocationCounter.java) via HotSpot's
`com.sun.management.ThreadMXBean#getThreadAllocatedBytes`). For deeper work, reach for **JFR** (Chunk 0,
§6) — always-on, ~1% overhead — to get allocation flame graphs and GC-phase timelines:

```bash
./gradlew :tuning:run -q \
  -Dorg.gradle.jvmargs="-XX:StartFlightRecording=filename=tuning.jfr,settings=profile"
jfr summary tuning.jfr
jfr print --events jdk.GCPhasePause,jdk.ObjectAllocationSample tuning.jfr
```

and **async-profiler** for low-overhead CPU/alloc flame graphs (`-agentpath:.../libasyncProfiler.so=...`).
The principle is unchanged from Chunk 0: **profile first, tune second** — the `bench*` tasks and the
JFR/profiler hooks are the loop that keeps "make it fast" from becoming superstition.

---

## Key takeaways

1. **Measurement becomes a tuning loop.** One reusable harness, paced and coordinated-omission-safe,
   reports percentiles *with* GC pauses and bytes-allocated next to them — so a tail spike is attributable.
2. **The hot path allocates ~nothing** (~4 KB per *million* orders); the **Epsilon no-op GC** completing
   the run is the proof, and a guard against allocation regressions.
3. **Collector choice depends on the allocation profile.** With zero garbage, all you measure is each
   collector's barrier overhead — and ZGC's load barriers make it the *wrong* pick for an allocation-free
   nanosecond path, despite being the right pick for huge heaps that can't tolerate a G1 pause.
4. **CPU affinity** removes scheduler/cache-migration jitter — but only truly on **Linux with isolated
   cores**; on macOS it's advisory. Pin the latency-critical thread; isolate its core.
5. **A laptop's tail is noisy by nature.** You tighten tails by controlling the *environment* (core
   isolation, pinning, CPU governor), not by editing already-allocation-free code. Trust medians and
   GC/alloc anywhere; trust tails only on a tuned, isolated machine.

## Next: Chunk 8 — Aeron transport + order-entry gateway

We push past the single JVM: an **Aeron** (UDP/IPC, reliable low-latency messaging) transport and an
order-entry **gateway**, so orders arrive over the wire instead of an in-process loop — and the
measurement discipline of this chunk follows them across the network boundary.
