// streaming: the analytics tier. Publishes the engine's trade output to Kafka (off the hot path) and
// computes live windowed analytics (OHLC candles, VWAP, trade-flow imbalance) in Apache Flink.
//
// This module lives OUTSIDE the low-latency core on purpose: Kafka and Flink trade nanoseconds for
// durability, scale-out, and operability. Two runnable mains: the Flink job (application `run`) and
// the engine→Kafka producer (`runProducer`).
plugins {
    `java-library`
    application
}

// Flink bundles org.lz4:lz4-java while kafka-clients 4.x uses the at.yawk.lz4 fork; both declare the
// same `lz4-java` capability, which Gradle flags as a conflict. They are drop-in compatible (same
// net.jpountz packages), so resolve to the highest version.
configurations.all {
    resolutionStrategy.capabilitiesResolution.withCapability("org.lz4:lz4-java") {
        selectHighestVersion()
    }
}

dependencies {
    api(project(":engine-disruptor"))   // feed the engine, listen to its trades
    implementation(libs.jackson.databind)

    // Flink (DataStream API + local execution + Kafka connector)
    implementation(libs.flink.streaming)
    implementation(libs.flink.clients)
    implementation(libs.flink.connector.kafka)

    // Kafka producer for the engine→Kafka side
    implementation(libs.kafka.clients)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("com.lowlatency.streaming.TradeAnalyticsJob")
}

// Flink's runtime reflects into JDK internals; on Java 17+ these module flags are required for the
// embedded MiniCluster (tests) and any local run.
val flinkJvmArgs = listOf(
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.time=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
)

tasks.withType<Test>().configureEach {
    jvmArgs(flinkJvmArgs)
}
tasks.withType<JavaExec>().configureEach {
    jvmArgs(flinkJvmArgs)
}

// Second runnable main: the engine→Kafka producer.
tasks.register<JavaExec>("runProducer") {
    group = "application"
    description = "Replay an order flow through the engine and publish its trades to Kafka."
    mainClass.set("com.lowlatency.streaming.EngineToKafkaApp")
    classpath = sourceSets["main"].runtimeClasspath
}
