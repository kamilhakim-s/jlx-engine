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
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("com.lowlatency.engine.OrderBookDemo")
}
