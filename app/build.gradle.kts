// app: Chunk 9 — the capstone. Wires the whole system into one runnable, infra-free pipeline:
// synthetic/real market data → Aeron gateway → matching engine (+ parallel Chronicle journal) →
// trade seam → in-process windowed candle analytics, all measured end-to-end. Reuses every prior
// module rather than re-implementing anything.
plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":engine-core"))
    implementation(project(":engine-disruptor"))
    implementation(project(":market-data"))  // AggTrade + the public-trade→order reconstruction
    implementation(project(":journal"))       // CommandJournaller (parallel Disruptor consumer)
    // Reuse Chunk 6's AsyncTradeForwarder seam + windowed analytics maths (plain Java classes), but
    // exclude the Flink/Kafka frameworks: the capstone runs the analytics IN-PROCESS and infra-free,
    // and dragging them in re-triggers Chunk 6's lz4 capability clash on this classpath. This exclusion
    // *is* the Chunk 6 lesson made literal — the streaming tier lives outside the low-latency core.
    implementation(project(":streaming")) {
        exclude(group = "org.apache.flink")
        exclude(group = "org.apache.kafka")
    }
    implementation(project(":gateway"))        // Aeron transport + order-entry gateway
    implementation(project(":tuning"))         // GcStats / AllocationCounter observability
    implementation(libs.aeron.client)
    implementation(libs.aeron.driver)
    implementation(libs.disruptor)   // ProducerType / WaitStrategy to build the engine with both consumers
    implementation(libs.hdrhistogram)
    // Several deps (Chronicle, affinity) drag SLF4J; api resolves to 2.0.x here, so pin a matching no-op
    // provider (tuning's transitive 1.7.x binding is ignored against a 2.x api → console warnings).
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("com.lowlatency.app.CapstoneApp")
}

// The engine carries a Chronicle Queue journaller (Chunk 5), which reaches into JDK internals — the
// same module flags the journal module needs apply to anything that boots the journaller.
val chronicleJvmArgs = listOf(
    "-Dchronicle.analytics.disable=true",
    "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED",
    "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
)

tasks.withType<Test>().configureEach {
    jvmArgs(chronicleJvmArgs)
}
tasks.withType<JavaExec>().configureEach {
    jvmArgs(chronicleJvmArgs)
}
