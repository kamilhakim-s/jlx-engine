// engine-core: the matching engine itself. Chunk 1 adds a correct, single-threaded
// limit order book; Chunk 2 adds a zero-allocation variant alongside it.
//
// `java-library` so we can distinguish api/implementation deps; `application` so the
// scripted demo can be run with `./gradlew :engine-core:run`.
plugins {
    `java-library`
    application
}

dependencies {
    // Low-latency collections used by the Chunk 2 zero-allocation engine. Kept as
    // implementation deps — they don't leak into engine-core's public API.
    implementation(libs.agrona)     // primitive-keyed Long2ObjectHashMap (id index)
    implementation(libs.fastutil)   // primitive-keyed sorted Long2ObjectRBTreeMap (price levels)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("com.lowlatency.engine.OrderBookDemo")
}
