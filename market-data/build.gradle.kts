// market-data: pulls real Binance market data — bulk historical aggregate trades (the large-dataset
// pull) and the live WebSocket stream — normalises it into integer-money events, and replays it into
// the Disruptor matching engine. Networking uses the JDK's built-in java.net.http client (REST +
// WebSocket); only JSON parsing needs a dependency. `application` so the demo runs via :run.
plugins {
    `java-library`
    application
}

dependencies {
    api(project(":engine-core"))        // Side, OrderType, integer-money types
    implementation(project(":engine-disruptor")) // publish into the ring buffer
    implementation(libs.jackson.databind)        // parse live @aggTrade JSON
    implementation(libs.hdrhistogram)            // latency in the replay demo

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("com.lowlatency.marketdata.MarketDataDemo")
}
