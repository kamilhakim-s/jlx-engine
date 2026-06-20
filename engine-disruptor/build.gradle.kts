// engine-disruptor: wraps the single-threaded engine-core matching engine behind an LMAX Disruptor
// ring buffer, turning it into a single-writer service fed by a lock-free queue. `application` so the
// latency demo runs with `./gradlew :engine-disruptor:run`.
plugins {
    `java-library`
    application
}

dependencies {
    api(project(":engine-core"))
    implementation(libs.disruptor)
    implementation(libs.hdrhistogram)   // end-to-end latency measurement in the demo/handler

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("com.lowlatency.engine.disruptor.DisruptorLatencyDemo")
}
