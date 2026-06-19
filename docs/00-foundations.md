# Chunk 0 — Foundations & the Measurement Harness

> **Running documentation.** This file teaches the concepts behind the code we wrote in Chunk 0.
> Read it top to bottom; run the commands as you go. By the end you'll understand *why* a
> low-latency engineer builds the measurement harness **before** the system being measured.

---

## What we built

```
low-latency/
├─ settings.gradle.kts        ← multi-module build, currently includes :benchmarks
├─ build.gradle.kts           ← shared config: Java 21 toolchain for every module
├─ gradle/libs.versions.toml  ← version catalog (single source of truth for deps)
├─ gradle.properties          ← build performance + a note on JDK selection
└─ benchmarks/
   ├─ build.gradle.kts
   └─ src/
      ├─ main/java/com/lowlatency/bench/CoordinatedOmissionDemo.java   ← the headline lesson
      └─ jmh/java/com/lowlatency/bench/LookupBenchmark.java            ← first JMH benchmark
```

Two runnable things, two purposes:

| Command | What it is | Teaches |
|---|---|---|
| `./gradlew :benchmarks:run` | A full program (the CO demo) | How naive measurement *lies* about the tail |
| `./gradlew :benchmarks:jmh` | JMH microbenchmarks | How to measure nanosecond-scale code honestly |

---

## 1. Mechanical sympathy — the mindset shift

You come from stateless Spring Boot services where the playbook is "add a pod." That's **scaling
throughput horizontally**. Low latency is a different game: making a *single* request finish in as
few nanoseconds as possible, predictably, every time — including the 1-in-10,000 request.

The term **mechanical sympathy** (from racing driver Jackie Stewart, popularised by Martin Thompson)
means writing software that works *with* the hardware rather than against it: CPU caches, branch
predictors, memory layout, the allocator, the garbage collector. A modern CPU can do ~3 billion
simple ops/second per core, but a single main-memory access (cache miss) costs ~100 ns — time enough
for hundreds of instructions. In LL, your enemies are not "slow algorithms" so much as **cache
misses, allocation, GC pauses, lock contention, and context switches**. The rest of this project is
a guided tour of defeating each one.

> **The discipline:** *make it correct → make it measurable → make it fast.* We do **measurable**
> first (this chunk) and **correct** next (Chunk 1). We do not optimise until Chunk 2, because
> optimising without measurement is superstition.

---

## 2. Latency vs throughput (don't conflate them)

- **Throughput** = how many requests per second the system completes. (Your microservices world.)
- **Latency** = how long *one* request takes, end to end.

They are not reciprocals. A system can have huge throughput *and* a terrible tail latency (e.g. it
batches work, so individual requests wait). In an exchange, a 99.9th-percentile latency spike is a
trade that filled late at a worse price — real money. So we obsess over **the distribution of
latency**, not the average.

---

## 3. Percentiles and the tail — why the average is useless

The average hides exactly what matters. Consider our demo's output:

```
percentile    Naive (lie)  Hdr-corrected   True latency
p50                250 µs         250 µs         250 µs
p99                250 µs         250 µs         250 µs
p99.9              250 µs      40,009 µs      41,779 µs
p99.99             250 µs      49,020 µs      49,250 µs
max             50,003 µs      50,003 µs      50,003 µs
mean               255 µs         375 µs         401 µs
```

Look at the **True latency** column. The mean is 401 µs — sounds fine. But p99.9 is **41,779 µs**
(≈ 42 ms). That means 1 in every 1,000 requests waited ~42 ms. If you process millions of orders a
day, "1 in 1,000" is thousands of furious customers. **Always report p50 / p99 / p99.9 / p99.99 /
max — never the mean.** This is why we use [HdrHistogram](https://github.com/HdrHistogram/HdrHistogram):
it records every value into a compressed, fixed-precision histogram so percentiles are exact and
cheap, even across billions of samples.

---

## 4. Coordinated Omission — the bug in almost everyone's benchmarks

This is the headline lesson of Chunk 0. Run it:

```bash
./gradlew :benchmarks:run
```

The scenario in [`CoordinatedOmissionDemo.java`](../benchmarks/src/main/java/com/lowlatency/bench/CoordinatedOmissionDemo.java):
a single-threaded server should handle **one request every 1 ms**. Almost all requests take 250 µs,
but every 10,000th request the server stalls for **50 ms** (think: a GC pause).

During that 50 ms stall, ~50 requests that were *due* can't even start — they queue up behind the
stall. The honest question is: *what latency did those 50 victims experience?* Tens of milliseconds,
because they sat waiting.

A **naive** measurement times only the work the server actually performed per request. It sees one
slow request (50 ms) and 50 normal-looking ones — it never records the *waiting* the victims did,
because the measuring loop was itself blocked by the same stall. The measurement is *coordinated*
with the stall it should be measuring, so it **omits** the tail. That's **coordinated omission**, and
it makes broken systems look healthy. In the table above it's the `Naive (lie)` column: p99.9 = 250 µs.
A flat-out lie.

Two ways to tell the truth, both shown in the demo:

1. **Measure from the *intended* start time**, not when the server got around to the request
   (`True latency` column). This is the gold standard — it's what the caller actually felt.
2. **`HdrHistogram.recordValueWithExpectedInterval(value, expectedInterval)`** (`Hdr-corrected`
   column). Given the expected cadence, HdrHistogram synthesises the samples that *must* have been
   waiting during a long operation. Useful when you can only measure service time but know the rate.

> **Rule adopted for the whole project:** latency is always measured from when work was *supposed*
> to begin, and reported as percentiles. When we benchmark the matching engine (Chunk 3, Chunk 7)
> we feed orders at a fixed rate and time from the intended send instant — never from "when the
> engine dequeued it."

---

## 5. JMH — honest microbenchmarks

For nanosecond-scale code (a map lookup, a match step) you cannot just wrap `System.nanoTime()`
around a loop — the JIT will optimise your benchmark away, or measure cold/unwarmed code. **JMH**
(Java Microbenchmark Harness) handles the traps: JVM warmup, multiple forks (fresh JVMs to average
out JIT/GC luck), and `Blackhole` to stop the compiler from deleting "useless" results.

Run it:

```bash
./gradlew :benchmarks:jmh
```

[`LookupBenchmark.java`](../benchmarks/src/jmh/java/com/lowlatency/bench/LookupBenchmark.java)
compares reading a value by `int` key from an `int[]` vs a `HashMap<Integer,Integer>`. Our run:

```
Benchmark                   Mode  Cnt  Score   Error  Units
LookupBenchmark.arrayGet    avgt    5  0.647 ± 0.009  ns/op
LookupBenchmark.hashMapGet  avgt    5  1.804 ± 0.006  ns/op
```

The `HashMap` is ~2.8× slower — it hashes, chases a pointer to a bucket (likely a cache miss), and
**autoboxes** the `int` key into an `Integer` object on every call. That boxing/pointer-chasing tax
is exactly what we'll strip out of the order book's hot path in **Chunk 2** using primitive-keyed
collections. Keep these numbers; we'll beat them.

> **JMH vocabulary:** `@Warmup` (let the JIT compile hot code before timing), `@Fork` (run in a
> separate JVM, repeated to average out variance), `@BenchmarkMode(AverageTime)` (ns per op),
> `Blackhole.consume(...)` (defeat dead-code elimination). The harness config lives in
> `benchmarks/build.gradle.kts` (`warmupIterations`, `iterations`, `fork`).

---

## 6. JFR — the always-on profiler

[Java Flight Recorder](https://docs.oracle.com/en/java/javase/21/jfdg/flight-recorder.html) (JFR)
ships in the JDK and records allocations, GC pauses, locks, and CPU samples with ~1% overhead — low
enough to leave on in production. We'll lean on it heavily in Chunk 2 (allocation profiling) and
Chunk 7 (tuning). To capture a recording of any run:

```bash
# Record the CO demo to a .jfr file, then open it in JDK Mission Control (JMC) or `jfr print`.
./gradlew :benchmarks:run -q \
  -Dorg.gradle.jvmargs="-XX:StartFlightRecording=duration=10s,filename=demo.jfr,settings=profile"
# Inspect from the CLI:
jfr summary demo.jfr        # high-level event counts
jfr print --events jdk.GCPhasePause demo.jfr
```

(`.jfr` files are git-ignored.) You don't need to master JFR now — just know it exists and that
"profile first, optimise second" is non-negotiable. We'll do real allocation flame graphs next chunk.

---

## 7. How to run everything (cheat sheet)

```bash
./gradlew :benchmarks:run      # coordinated-omission demo (the tail-latency lesson)
./gradlew :benchmarks:jmh      # JMH microbenchmarks (array vs HashMap lookup)
./gradlew build                # compile + test everything
```

If `./gradlew` ever launches under the wrong JDK, prefix with
`JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

---

## Key takeaways

1. **Measure before you optimise** — the harness is built first, on purpose.
2. **The average lies; the tail is the truth.** Report p99 / p99.9 / p99.99, not the mean.
3. **Coordinated omission** makes naive benchmarks hide the tail. Always time from the *intended*
   start; use HdrHistogram's percentiles and `recordValueWithExpectedInterval` where appropriate.
4. **JMH** for nanosecond code (warmup, forks, blackholes); **JFR** for whole-program profiling.
5. Boxing + pointer-chasing already cost us ~2.8× on a single lookup — a preview of Chunk 2.

---

## Next: Chunk 1 — the order book

We'll build a **correct, single-threaded limit order book** with price-time-priority matching, fully
unit-tested. No optimisation yet — correctness first. Then in Chunk 2 we point this measurement
harness at it and start making it fast.

To start the next session:
> *"Read the plan and `docs/` so far, then implement Chunk 1."*
