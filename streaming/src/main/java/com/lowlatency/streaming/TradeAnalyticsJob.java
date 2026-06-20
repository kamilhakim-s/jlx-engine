package com.lowlatency.streaming;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.time.Duration;

/**
 * The Flink job: consume the {@code trades} Kafka topic, assign event time, and emit one candle per
 * symbol per 10-second window. Run after starting Kafka and the producer:
 *
 * <pre>
 *   docker compose -f streaming/docker-compose.yml up -d     # Kafka (KRaft, single broker)
 *   ./gradlew :streaming:runProducer                         # engine trades → Kafka
 *   ./gradlew :streaming:run                                 # this job → prints candles
 * </pre>
 *
 * <p>Configurable via {@code -Dkafka.bootstrap=...} and {@code -Dkafka.topic=...}.
 */
public final class TradeAnalyticsJob {

    public static void main(String[] args) throws Exception {
        String bootstrap = System.getProperty("kafka.bootstrap", "localhost:9092");
        String topic = System.getProperty("kafka.topic", "trades");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(topic)
                .setGroupId("trade-analytics")
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        // Source watermarks are assigned after parsing, on the event's own timestamp. We tolerate up
        // to 2s of out-of-orderness before a window is considered complete and fired.
        DataStream<TradeEvent> trades = env
                .fromSource(source, WatermarkStrategy.noWatermarks(), "kafka-trades")
                .map(TradeEventJson::fromJson)
                .returns(TradeEvent.class)
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy.<TradeEvent>forBoundedOutOfOrderness(Duration.ofSeconds(2))
                                .withTimestampAssigner((event, ts) -> event.timestampMillis()));

        TradeAnalytics.candles(trades, Duration.ofSeconds(10)).print();

        env.execute("trade-analytics");
    }

    private TradeAnalyticsJob() {
    }
}
