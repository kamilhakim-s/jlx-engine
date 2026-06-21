# Chunk 8 — Aeron Transport & the Order-Entry Gateway

> **Running documentation.** Every chunk so far fed the engine from an *in-process* loop — orders arrived
> as a method call on the same machine, often the same thread. Real exchanges receive orders **over the
> network**, from many remote clients. Chunk 8 crosses that boundary: orders arrive over an **Aeron**
> transport (UDP or shared-memory IPC), and an order-entry **gateway** decodes the wire message and
> publishes it into the Chunk 3 Disruptor ring buffer. The engine downstream doesn't change at all — and
> that's the point.

---

## What we built

```
gateway/src/main/java/com/lowlatency/gateway/
├─ OrderMessage.java        the wire format: a flat, fixed-offset binary codec over an Agrona buffer
├─ OrderEntryClient.java    encodes orders and offers them to an Aeron Publication (with backpressure)
├─ OrderGateway.java        Aeron Subscription → decode → publish into the DisruptorMatchingService
└─ GatewayDemo.java         embedded media driver + IPC: client → Aeron → gateway → engine, end-to-end latency
gateway/src/test/java/com/lowlatency/gateway/
├─ OrderMessageTest.java       wire codec round-trips at fixed offsets
└─ OrderGatewayIpcTest.java    full path over an embedded Aeron driver (orders matched; cancel removes liquidity)
```

Run it:
```bash
./gradlew :gateway:run     # client → Aeron (IPC) → gateway → engine; prints end-to-end percentiles
./gradlew :gateway:test    # codec round-trip + end-to-end over an embedded media driver (no network)

# Exercise the UDP network stack instead of shared memory:
./gradlew :gateway:run -Dgateway.channel="aeron:udp?endpoint=localhost:40123"
```

---

## 1. Why Aeron — and why not a socket

A plain TCP socket is the obvious way to get orders over the wire, and the wrong one for this job. TCP
gives you a byte *stream* (you re-frame messages yourself), head-of-line blocking, Nagle's algorithm, a
kernel-space copy on every `read`/`write`, and a tail latency that wanders into milliseconds under load.

[**Aeron**](https://github.com/aeron-io/aeron) (Real Logic — Martin Thompson again, of Disruptor fame) is
a messaging transport built for exactly our constraints:

- **Message-oriented**, not stream-oriented: it delivers your 40-byte order as one *fragment*, framed for
  you. No re-assembly logic on the hot path.
- **The same API over UDP or IPC.** `aeron:udp?endpoint=host:port` goes over the network; `aeron:ipc`
  goes through a **shared-memory** ring buffer between processes on one box, with *no* kernel transition.
  Switching transport is a one-line channel string change — the demo flips between them with a system
  property.
- **A separate process, the *media driver*, owns the buffers.** Publishers and subscribers map the same
  memory-mapped log; the driver handles flow control, retransmission (UDP), and term-buffer management.
  We launch it **embedded** (`MediaDriver.launchEmbedded`) so the demo is self-contained, but in
  production it runs as its own process pinned to its own cores.
- **Mechanically sympathetic by construction** — the log is a pre-allocated power-of-two ring (the same
  shape as the Disruptor), publishing is a claim-and-commit, and the subscriber drains fragments in
  batches. The lessons of Chunks 2–3 reappear at the transport layer.

## 2. The wire format — a flat binary message, decoded in place

[`OrderMessage`](../gateway/src/main/java/com/lowlatency/gateway/OrderMessage.java) is the protocol: a
**fixed 40-byte layout** with every field at a known offset.

```
off 0  version (1B)   off 1  type (1B)   off 2  side (1B)   off 3  orderType (1B)   [pad to 8]
off 8  orderId (8B)   off 16 price (8B)  off 24 quantity (8B)  off 32 ingressNanos (8B)
```

Two properties make it fast, and they're the Chunk 2 zero-allocation lesson applied to the network:

- **Fixed-offset ⇒ decoding is a few aligned reads.** No parsing, no scanning for delimiters, no
  branching on structure — `buffer.getLong(offset + PRICE_OFFSET)` and you're done. (The four header bytes
  are padded out to an 8-byte boundary so the `long` fields are aligned: aligned 64-bit reads are cheaper
  and never tear.)
- **Zero-copy, zero-allocation to decode.** The accessors read **straight from the buffer Aeron hands the
  gateway**, at the fragment's offset. We never materialise an `OrderMessage` object — there is nothing
  for the GC to collect per order. The encode side reuses one off-heap `UnsafeBuffer` per client.

This is a deliberately tiny, hand-rolled version of **SBE** (Simple Binary Encoding) — the canonical
low-latency codec, which generates exactly this kind of flat accessor from an XML schema, with field
versioning and forward/backward compatibility handled for you. We hand-roll to keep the chunk about the
transport; reach for SBE the moment the protocol needs to evolve.

> **Endianness.** We use the buffer's native byte order — fast, and all the same-machine IPC demo needs.
> A real cross-host protocol pins an explicit endianness (SBE defaults to little-endian) so a big-endian
> peer decodes correctly. One of those details that's invisible until it bites.

## 3. The gateway — moving the *source* of commands to the wire

[`OrderGateway`](../gateway/src/main/java/com/lowlatency/gateway/OrderGateway.java) owns an Aeron
`Subscription` and a dedicated **poll thread**. The loop is the canonical Aeron consumer:

```java
while (running) {
    int fragments = subscription.poll(fragmentHandler, FRAGMENT_LIMIT);
    idleStrategy.idle(fragments);   // spin / yield / park when idle — the latency/CPU dial again
}
```

For each fragment, the handler decodes the message in place and calls `engine.publishNewOrder(...)` /
`publishCancel(...)`. The crucial architectural point:

> The gateway thread is the **single writer** to the ring buffer. We didn't change the engine — we changed
> where its commands come from. Chunks 3–7 published into the ring from an in-process loop; now the loop
> is "drain Aeron and republish." The matching engine remains lock-free, single-threaded, and entirely
> unaware that orders crossed a network.

The `IdleStrategy` is the same latency/CPU trade-off as the Disruptor's wait strategy: `BusySpinIdleStrategy`
in the demo (lowest latency, burns a core), a `BackoffIdleStrategy` in the test (so CI doesn't peg a core).
Two small zero-allocation details earn their keep: we cache `Side.values()`/`OrderType.values()` once
(calling `values()` per message would allocate an array each time), and the `FragmentHandler` is a stored
field, not a fresh lambda per poll.

## 4. Backpressure is explicit — the sender decides

[`OrderEntryClient`](../gateway/src/main/java/com/lowlatency/gateway/OrderEntryClient.java) encodes into
one reused off-heap buffer and calls `Publication.offer(...)`. Unlike a blocking socket that silently
buffers without bound, **Aeron's `offer` returns a negative value** when the message can't be placed — the
subscriber is behind (`BACK_PRESSURED`), not yet connected (`NOT_CONNECTED`), or the driver is mid-admin
(`ADMIN_ACTION`). The publisher must decide what to do; nothing is hidden.

We spin-retry (the lowest-latency policy) and **count** the retries, so backpressure is an observable
health signal rather than an invisible stall. This is the same philosophy as Chunk 6's drop-on-overflow
forwarder — bounded, explicit flow control — just with a different policy (retry vs drop) for a different
purpose (don't lose an order vs don't block the engine). A real gateway might reject the order back to the
client (`CLOSED`/`MAX_POSITION_EXCEEDED` are treated as fatal here) rather than spin forever.

## 5. Measuring the full path

`./gradlew :gateway:run` launches an embedded driver, wires client → Aeron (IPC) → gateway → engine, and
measures **end-to-end** latency with the project's standard discipline (warm up untimed, pace to a fixed
rate, stamp each order with its *intended* send instant, report percentiles). The `ingressNanos` stamp
rides in the wire message and is passed through to the engine, so the engine's existing histogram now
times the **whole** path: client encode → Aeron → gateway decode → ring buffer → match.

A representative run (Apple Silicon, JDK 21, `aeron:ipc`, 1,000,000 orders at 500k/s — **your numbers will
differ; the shape is the lesson**):

```
End-to-end latency (client → Aeron → gateway → engine match):
  p50=0.21 µs   p99=0.83 µs   p99.9=7.09 µs   p99.99=53.06 µs   max=136.06 µs
  matched qty=1,200,000   publisher backpressure retries=1
```

Read it against the in-process engine from Chunks 3/7 (busy-spin, p50 ≈ 0.08 µs): the **shared-memory IPC
transport adds only ~130 ns to the median**. That's the payoff of a zero-copy, allocation-free path
through memory-mapped buffers — crossing a *process* boundary on the same box is nearly free. Swap the
channel to `aeron:udp?endpoint=localhost:40123` and the median rises (loopback UDP, kernel involvement)
but the *method* — and the engine — are identical.

> **Clock caveat.** Client and gateway share this JVM in the demo, so `System.nanoTime()` is directly
> comparable. Across hosts, the send and receive stamps come from *different* clocks; a real one-way
> latency measurement needs synchronised clocks (PTP/hardware timestamps), or you measure round-trip and
> halve. The methodology is sound; the clock source is the asterisk.

## 6. What was verified, and how

- **`OrderMessageTest`** — the codec round-trips every field, including decoding at a non-zero buffer
  offset (Aeron delivers fragments at arbitrary offsets into a shared log, so the accessors must honour
  the offset, not assume zero).
- **`OrderGatewayIpcTest`** — the real path on an **embedded media driver over `aeron:ipc`** (no network,
  no external process): 1,000 buys sent over Aeron all match a resting sell (`matchedQuantity == 1000`),
  and a `CANCEL` sent over the wire removes resting liquidity so a later buy finds nothing. This proves
  the transport + codec + gateway + engine compose correctly, not just the maths.

Embedded Aeron needs **no special JVM flags** on JDK 21 here — a pleasant contrast with Flink's
`--add-opens` tax in Chunk 6. The media driver writes its buffers under the OS temp dir and deletes them on
start and shutdown, so runs leave nothing behind.

---

## Key takeaways

1. **The engine didn't change — its command source did.** The gateway thread is the single writer into the
   ring buffer, so the lock-free, single-threaded engine works unmodified whether orders come from an
   in-process loop or across the network. That separation is the architectural win.
2. **Aeron over a socket:** message-framed, zero-copy, the same API over UDP and shared-memory IPC, with a
   media driver that owns pre-allocated ring buffers — mechanically sympathetic by design.
3. **A flat fixed-offset binary wire format** decodes in a few aligned reads, straight from the network
   buffer, with zero allocation — the Chunk 2 discipline applied to the wire. SBE is the production
   version of this.
4. **Backpressure is explicit:** `offer` returns negative and the *sender* chooses (retry / drop / reject).
   Nothing is silently buffered without bound.
5. **IPC adds ~130 ns to the median** end-to-end — crossing a process boundary through shared memory is
   nearly free; the cost (and the reason for all this care) shows up in the tail and over real UDP.

## Next: Chunk 9 — Capstone: end-to-end wiring

The final chunk assembles the whole system into one runnable demo: Binance market data (Chunk 4) →
gateway/Aeron (Chunk 8) → matching engine (Chunks 1–3) → journal (Chunk 5) → Kafka/Flink analytics
(Chunk 6), measured and tuned (Chunk 7) — the complete low-latency pipeline, top to bottom.
