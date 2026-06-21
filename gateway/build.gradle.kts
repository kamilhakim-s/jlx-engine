// gateway: Chunk 8 — push the engine past a single in-process loop. Orders arrive over an **Aeron**
// transport (UDP or shared-memory IPC) instead of a method call; an order-entry **gateway** decodes the
// wire message and publishes it into the Chunk 3 Disruptor ring buffer. `application` so the end-to-end
// latency demo runs with `./gradlew :gateway:run`.
plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":engine-core"))
    implementation(project(":engine-disruptor"))
    implementation(libs.aeron.client)   // Publication / Subscription / embedded MediaDriver client
    implementation(libs.aeron.driver)   // the media driver (we launch it embedded for the demo/tests)
    implementation(libs.hdrhistogram)   // end-to-end latency percentiles, same discipline as every chunk

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("com.lowlatency.gateway.GatewayDemo")
}
