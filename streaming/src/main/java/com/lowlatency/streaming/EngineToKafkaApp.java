package com.lowlatency.streaming;

import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import com.lowlatency.engine.disruptor.DisruptorMatchingService;

import java.time.Clock;
import java.util.Random;
import java.util.concurrent.locks.LockSupport;

/**
 * Producer side of the streaming demo: run an order flow through the matching engine and publish its
 * trades to Kafka via {@link AsyncTradeForwarder} + {@link KafkaTradeSink}. The trade publishing runs
 * on the forwarder thread, leaving the engine's consumer thread free.
 *
 * <pre>./gradlew :streaming:runProducer</pre>
 *
 * <p>It synthesises a continuous crossing order flow for {@code DURATION_SECONDS}, paced so trades
 * spread over real time and therefore populate multiple event-time windows downstream. Configure with
 * {@code -Dkafka.bootstrap=...}.
 */
public final class EngineToKafkaApp {

    private static final String SYMBOL = "BTCUSDT";
    private static final int DURATION_SECONDS = 30;
    private static final long ORDERS_PER_SECOND = 20_000;

    public static void main(String[] args) {
        String bootstrap = System.getProperty("kafka.bootstrap", "localhost:9092");
        String topic = System.getProperty("kafka.topic", "trades");

        KafkaTradeSink kafka = new KafkaTradeSink(bootstrap, topic);
        AsyncTradeForwarder forwarder = new AsyncTradeForwarder(SYMBOL, kafka, 1 << 16, Clock.systemUTC());
        DisruptorMatchingService engine = new DisruptorMatchingService(1 << 16, 1 << 16, forwarder);
        engine.start();

        System.out.printf("Publishing ~%,d trades/s for %ds to topic '%s' at %s%n",
                ORDERS_PER_SECOND, DURATION_SECONDS, topic, bootstrap);

        feed(engine);

        engine.shutdown();
        forwarder.close();
        kafka.close();
        System.out.printf("Done. engineTrades=%,d  droppedByForwarder=%,d%n",
                engine.tradeCount(), forwarder.droppedCount());
    }

    /** Continuous maker+taker crossing flow at a paced rate, so trades populate event-time windows. */
    private static void feed(DisruptorMatchingService engine) {
        Random rnd = new Random(1);
        long intervalNanos = 1_000_000_000L / ORDERS_PER_SECOND;
        long id = 1;
        long start = System.nanoTime();
        long deadline = start + DURATION_SECONDS * 1_000_000_000L;
        long next = start;

        while (System.nanoTime() < deadline) {
            long price = 100_000 + rnd.nextInt(50) - 25; // wander around 100,000 ticks
            long qty = 1 + rnd.nextInt(5);
            boolean buyTaker = rnd.nextBoolean();
            Side taker = buyTaker ? Side.BUY : Side.SELL;
            // Maker rests, taker crosses → one trade at this price/size.
            engine.publishNewOrder(id++, taker.opposite(), OrderType.LIMIT, price, qty, 0);
            engine.publishNewOrder(id++, taker, OrderType.LIMIT, price, qty, 0);

            next += intervalNanos;
            while (System.nanoTime() < next) {
                LockSupport.parkNanos(1_000);
            }
        }
    }

    private EngineToKafkaApp() {
    }
}
