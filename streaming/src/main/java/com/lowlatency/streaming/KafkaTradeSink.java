package com.lowlatency.streaming;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.Closeable;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * A {@link Consumer Consumer&lt;TradeEvent&gt;} that publishes each trade to Kafka, keyed by symbol so
 * all trades for an instrument land on the same partition (preserving per-symbol order). Used as the
 * sink behind {@link AsyncTradeForwarder}, so it runs on the forwarder thread, never the engine's.
 *
 * <p>The producer is configured for a sane throughput/latency balance: {@code linger.ms} lets it batch
 * a few records, {@code acks=1} waits for the leader only. {@code send} is itself asynchronous (it
 * buffers and returns), so even on the forwarder thread it doesn't block per record.
 */
public final class KafkaTradeSink implements Consumer<TradeEvent>, Closeable {

    private final Producer<String, String> producer;
    private final String topic;

    public KafkaTradeSink(String bootstrapServers, String topic) {
        this(defaultProducer(bootstrapServers), topic);
    }

    /** Visible for testing with a mock/in-memory producer. */
    public KafkaTradeSink(Producer<String, String> producer, String topic) {
        this.producer = producer;
        this.topic = topic;
    }

    private static Producer<String, String> defaultProducer(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        return new KafkaProducer<>(props);
    }

    @Override
    public void accept(TradeEvent event) {
        producer.send(new ProducerRecord<>(topic, event.symbol(), TradeEventJson.toJson(event)));
    }

    @Override
    public void close() {
        producer.flush();
        producer.close();
    }
}
