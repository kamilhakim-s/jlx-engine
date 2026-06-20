# Chunk 1 — The Limit Order Book (correct first)

> **Running documentation.** Chunk 1 builds the heart of the engine: a **correct, single-threaded**
> limit order book with price-time-priority matching. No optimisation yet — we earn correctness and a
> thorough test suite first, then Chunk 2 makes it fast and Chunk 3 makes it concurrent.

---

## What we built

```
engine-core/src/main/java/com/lowlatency/engine/
├─ Side.java            BUY / SELL (+ opposite())
├─ OrderType.java       LIMIT / MARKET
├─ Order.java           mutable order; integer price & quantity
├─ Trade.java           read-only trade view (interface)
├─ TradeRecord.java     immutable Trade (Chunk 1's implementation)
├─ TradeHandler.java    push callback: onTrade(Trade)
├─ CollectingTradeHandler.java   test/demo helper that copies trades into a list
├─ OrderBook.java       resting liquidity: price levels + id index
├─ MatchingEngine.java  the matching algorithm
└─ OrderBookDemo.java   scripted walk-through (./gradlew :engine-core:run)

engine-core/src/test/java/com/lowlatency/engine/
└─ MatchingEngineTest.java   ~20 behavioural tests
```

Run it:
```bash
./gradlew :engine-core:test    # the test suite
./gradlew :engine-core:run     # the scripted demo (output reproduced below)
```

---

## 1. What a limit order book is

An exchange doesn't "set" a price — it runs a **continuous double auction**. Buyers post **bids**
(I'll buy up to N units at price P or lower) and sellers post **asks/offers** (I'll sell N units at P
or higher). All resting orders form the **limit order book (LOB)**:

```
        ASKS (sellers)          ← lowest ask = best ask (cheapest to buy from)
  103 | ####
  102 | ####
  101 | ######           ← the "touch" / top of book
  ----+--------  spread  
   99 | ##########       ← highest bid = best bid (most you can sell into)
   98 | #######
        BIDS (buyers)
```

The gap between best bid (99) and best ask (101) is the **spread**. A new order that is priced to
trade immediately against the other side is **aggressive** (it "crosses the spread" and *takes*
liquidity); one that rests is **passive** (it *makes* liquidity). The aggressor is the **taker**, the
resting order it hits is the **maker**.

## 2. Price-time priority — the matching rule

When an order can match, which resting order gets filled first? The near-universal rule is
**price-time priority**:

1. **Price priority** — better-priced resting orders match first (lowest ask / highest bid).
2. **Time priority** — within the *same* price, the order that arrived first matches first (FIFO).

So our data structures must answer "best price first" and "oldest first within a price" efficiently.
In Chunk 1 we choose the most readable structures that do this:

| Need | Structure (Chunk 1) | Why |
|---|---|---|
| Price priority | `TreeMap<Long, …>` per side (bids reverse-ordered) | sorted ⇒ best price is `firstKey()` |
| Time priority | `ArrayDeque<Order>` per price level | FIFO queue ⇒ oldest at the head |
| O(1) cancel | `HashMap<Long, Order>` (id → order) | jump straight to a resting order |

These are the *obvious* choices and they are **correct**. They are also wasteful — every `long`
price is boxed into a `Long`, and every insert allocates tree/deque nodes. Hold that thought; it's
the entire subject of Chunk 2. **Correctness first.**

## 3. The matching algorithm

In [`MatchingEngine.submit`](../engine-core/src/main/java/com/lowlatency/engine/MatchingEngine.java),
for an incoming order:

1. Walk the **opposite** side from the best price inward.
2. While the incoming order has quantity left **and** the resting price *crosses* (is acceptable):
   match against resting orders in time order; for each fill emit a `Trade` at the **maker's price**.
3. Stop when filled, the book is exhausted, or the next price no longer crosses.
4. A `LIMIT` remainder **rests**; a `MARKET` remainder is **discarded** (market orders never rest).

"Crosses" = an incoming buy accepts any ask `≤` its limit; an incoming sell accepts any bid `≥` its
limit; a market order accepts any price.

### Two subtleties worth internalising
- **Trades execute at the maker's price, not the taker's.** A buy limit @105 hitting a resting ask
  @100 fills at **100** — the passive order set the price; the aggressor gets price improvement. (Test:
  `tradeExecutesAtRestingMakerPrice`.)
- **Removal discipline.** While sweeping a level we `pollFirst()` filled makers off the deque and tell
  the book to `forgetResting(id)`; an emptied price level is pruned via the *iterator* we're walking
  (`levelIt.remove()`), never by a second map mutation. Mixing two removal paths on a `TreeMap`
  mid-iteration is a classic `ConcurrentModificationException` / double-free bug — we split the
  responsibility cleanly.

## 4. Integer money (no floats, ever)

`Order.price` and `Order.quantity` are `long`. Binary floating point cannot represent decimals like
`0.10` exactly, and matching money with rounding error is a non-starter. Exchanges use **integer
ticks**: pick a scale once (e.g. price in 0.01 USDT, quantity in 1e-8 BTC) and keep all arithmetic
integral. As a bonus, integer compares are branch-predictable and allocation-free — friendly to the
low-latency work ahead.

## 5. The demo, annotated

```
=== Aggressive BUY limit @102 for 8 units (sweeps 101 then 102) ===
  TRADE seq=0  taker#7 BUY  maker#2  qty=3 @ 101   ← best price (101) first…
  TRADE seq=1  taker#7 BUY  maker#3  qty=2 @ 101   ← …same price, older order #2 before #3 (time)
  TRADE seq=2  taker#7 BUY  maker#4  qty=3 @ 102   ← then the next level up, partially (3 of 4)
  book: bestBid=99 bestAsk=102                     ← 101 fully consumed; 1 unit left of #4 @102

=== MARKET SELL for 12 units (hits bids 99 then 98) ===
  TRADE seq=3  taker#8 SELL maker#5  qty=10 @ 99   ← market ignores price, takes best bid first
  TRADE seq=4  taker#8 SELL maker#6  qty=2  @ 98   ← remainder of the 12 sweeps into 98
```

Every line is price-time priority in action. The `seq` field is a monotonic engine counter we'll lean
on for deterministic replay in Chunk 5.

## 6. The test suite (your safety net for Chunk 2)

[`MatchingEngineTest`](../engine-core/src/test/java/com/lowlatency/engine/MatchingEngineTest.java)
covers resting/non-crossing, full and partial fills (both taker- and maker-side), price priority
across levels, FIFO time priority within a level, the "stop when it no longer crosses" boundary,
market-order sweep + remainder discard + empty book, and cancellation (resting, unknown id, already
filled, one-of-two-at-a-level). These tests are the **specification**. In Chunk 2 we rewrite the data
structures for speed; this suite (plus a randomised equivalence test) is what lets us do that with
confidence that behaviour didn't change.

---

## What's deliberately *not* here

- **No self-trade prevention** — needs account identity; added in a later chunk.
- **No concurrency** — the core is intentionally single-threaded. The single-writer concurrency model
  is the subject of Chunk 3 (the Disruptor), wrapped *around* a core like this.
- **No persistence** — Chunk 5 adds journaling and deterministic replay.

## Key takeaways

1. A LOB is a continuous double auction; matching follows **price-time priority**.
2. Best-price-first ⇒ a sorted structure per side; FIFO-within-price ⇒ a queue per level.
3. Trades print at the **maker's** price; the taker can get price improvement.
4. Use **integer money** — never floating point — for prices and quantities.
5. We optimised for **clarity and a strong test suite**, knowing Chunk 2 will trade these structures
   for allocation-free ones, guarded by these exact tests.

## Next: Chunk 2 — make it allocation-free

We point the Chunk 0 measurement harness at this engine, profile its allocations, then build a second
engine using primitive-keyed collections, an intrusive linked list, object pools, and a reused trade
flyweight — and prove with JMH that it drops to ~0 B/op on the match path while producing byte-for-byte
identical trades.
