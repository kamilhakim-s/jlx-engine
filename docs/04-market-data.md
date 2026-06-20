# Chunk 4 — Real Market Data from Binance

> **Running documentation.** Chunk 4 connects the engine to the real world: a **bulk historical**
> pull of millions of trades from Binance's public archive, a **live WebSocket** stream, a single
> normalised event shape for both, and a **replayer** that feeds them into the Chunk 3 ring buffer.
> No third-party HTTP library — just the JDK's `java.net.http`.

---

## What we built

```
market-data/src/main/java/com/lowlatency/marketdata/
├─ Decimals.java                  decimal-string → scaled long (integer money, no doubles)
├─ SymbolScale.java               per-symbol price/quantity scales (BTCUSDT = 2 / 8)
├─ AggTrade.java                  the normalised event (both sources emit this)
├─ AggTradeCsvParser.java         parse a historical CSV line
├─ AggTradeJsonParser.java        parse a live @aggTrade JSON message (Jackson)
├─ BinanceHistoricalDownloader.java  download + stream the daily ZIP (the large pull)
├─ BinanceLiveClient.java         JDK WebSocket client for the live stream
├─ MarketDataReplayer.java        AggTrade → ring-buffer order commands (Consumer<AggTrade>)
└─ MarketDataDemo.java            historical replay + live modes
```

Run it:
```bash
./gradlew :market-data:test                  # parsing + replay (no network)
./gradlew :market-data:run                   # download a day of BTCUSDT and replay through the engine
./gradlew :market-data:run --args="live"     # 20 seconds of the live stream
```

---

## 1. Two sources, one shape

The design principle: normalise at the edge. Both data sources are converted to the same internal
[`AggTrade`](../market-data/src/main/java/com/lowlatency/marketdata/AggTrade.java) record the moment
they arrive, so nothing downstream — the replayer, the engine, later the streaming tier — ever knows
or cares whether a trade came from a file or a socket.

| | Historical (bulk) | Live (stream) |
|---|---|---|
| Source | `data.binance.vision` daily ZIP of CSV | `wss://stream.binance.com/.../@aggTrade` |
| Transport | HTTPS GET (JDK `HttpClient`) | WebSocket (JDK `HttpClient`) |
| Format | CSV rows | JSON messages |
| Volume | millions/day, free, unauthenticated | real-time, unbounded |
| Use | replay, backtest, benchmark | live matching |

An **aggregate trade** ("aggTrade") is Binance collapsing consecutive fills of one resting order at one
price into a single print. It carries price, quantity, time, and `buyerIsMaker` — enough to know the
**taker side** (`buyerIsMaker ? SELL : BUY`), which is what the replayer needs.

## 2. Integer money survives the edge

Binance sends prices and sizes as decimal *strings* (`"42123.45"`, `"0.01"`). If we parsed those with
`Double.parseDouble` we'd reintroduce exactly the floating-point error Chunk 1 banned.
[`Decimals.parseScaled`](../market-data/src/main/java/com/lowlatency/marketdata/Decimals.java) converts
a decimal string to a scaled `long` using pure integer arithmetic on substrings — `"42123.45"` at
scale 2 becomes `4_212_345`. [`SymbolScale`](../market-data/src/main/java/com/lowlatency/marketdata/SymbolScale.java)
holds the per-symbol scales (BTCUSDT: price 0.01, quantity 1e-8). The engine still only ever sees
integers.

## 3. The big pull — streaming a ZIP without loading it

[`BinanceHistoricalDownloader`](../market-data/src/main/java/com/lowlatency/marketdata/BinanceHistoricalDownloader.java)
fetches a day's ZIP once (cached under `data/`, git-ignored) and then **streams** it: it reads the ZIP
entry line by line and pushes each `AggTrade` to a `Consumer`, never holding more than one line in
memory. That's the discipline that lets you pull *gigabytes* — memory stays flat whether the file is 10
MB or 10 GB. (One BTCUSDT day is ~12 MB zipped ≈ ~900k–several million trades.)

Newer Binance files quote `transactTime` in microseconds and some include a header row; the parser
handles both (normalising 16-digit times to millis, skipping a non-numeric first line).

## 4. The live stream — fragments and flow control

[`BinanceLiveClient`](../market-data/src/main/java/com/lowlatency/marketdata/BinanceLiveClient.java)
uses the JDK `WebSocket`. Two real-world details the code handles:

- **Message fragmentation.** `onText` can be called with `last == false`; we accumulate fragments per
  message and only parse on the final frame.
- **Flow control / backpressure.** After each delivery we call `webSocket.request(1)` to ask for the
  next message — the JDK client won't push faster than we consume.

## 5. The replayer — turning trades into engine activity

[`MarketDataReplayer`](../market-data/src/main/java/com/lowlatency/marketdata/MarketDataReplayer.java)
is a `Consumer<AggTrade>`, so it plugs straight into both the downloader's `stream` and the live
client's callback — **the same replayer drives historical and live**.

The honest modelling problem: Binance's public feed gives **trade prints**, not the order-by-order
book (that's L3 data Binance doesn't publish). To exercise the engine on the real price and size of
each print, the replayer reconstructs each trade as a **crossing pair**: publish a resting *maker*
limit order on the side that rested, then the *taker* limit order at the same price. The taker crosses
the maker, the engine emits exactly that trade at the real price/quantity, and the book nets back to
empty — so memory stays bounded across millions of trades. (Faithful continuous-book reconstruction is
deferred; it needs L3 order data.)

**Backpressure is automatic and free**: publishing claims a ring-buffer slot via `next()`, which blocks
if the single-writer engine has fallen behind. A fast file reader can never outrun the engine or grow an
unbounded queue — the ring buffer is the flow-control valve.

## 6. The demo — real numbers

Running the historical replay against one real day of BTCUSDT (Apple Silicon, JDK 21):

```
Replayed 923,881 source trades in 3.44s (268,684 trades/s through the engine)
engine trades=923,881  matched units=2,963,337,400,000
end-to-end latency  p50=0.13 µs  p99=0.58 µs  p99.9=48.64 µs  max=1544.19 µs
```

Read it critically — there are three lessons hiding in those numbers:

1. **`engine trades == source trades` (923,881 = 923,881).** The reconstruction is faithful: every real
   print produced exactly one engine trade at the real price and size. That's our correctness check on
   the whole pipeline.
2. **The bottleneck is ingestion, not the engine.** 269k trades/s is gated by the single-threaded HTTPS
   download + CSV parse; the engine itself (Chunk 3) does *millions* of orders/s. This is *why* heavy
   data work belongs in a separate tier from the latency-critical core — the theme of Chunk 6.
3. **The tail (max 1.5 ms) is GC, not matching.** The CSV parser allocates per line (`split`, boxed
   times), creating young-gen garbage; a minor GC pause stalls the consumer and shows up as a latency
   spike — a live demonstration of the Chunk 2 lesson that *allocation anywhere near the hot path
   becomes tail latency*. The fix isn't to micro-optimise the parser (it's off the hot path on purpose)
   but to keep ingestion and matching on separate threads/cores (Chunk 7).

The live mode (`--args="live"`) connects to the public stream and replays trades as they arrive, paced
naturally by the market.

---

## Key takeaways

1. **Normalise at the edge**: convert both bulk and live data to one internal event (`AggTrade`) so
   nothing downstream cares about the source.
2. Keep **integer money** all the way in — parse decimal strings with integer arithmetic, never
   `double`.
3. **Stream, don't slurp**: line-by-line ZIP parsing keeps memory flat for arbitrarily large pulls.
4. The Disruptor's `next()` gives you **backpressure for free** between a fast producer and the engine.
5. The engine isn't the bottleneck — **ingestion is**, and ingestion allocation shows up as **tail
   latency**. That separation of concerns motivates the streaming tier next.

## Next: Chunk 5 — journaling & deterministic replay

Before we scale out, we make the engine durable: persist the inbound command stream to a
Chronicle Queue on the hot path and rebuild engine state by deterministic replay after a crash — the
event-sourcing model real exchanges use.
