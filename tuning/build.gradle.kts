// tuning: Chunk 7 — benchmarking, tuning & observability. We return to the low-latency core and pull
// the whole toolkit together: a configurable end-to-end latency suite, GC experiments (run the SAME
// workload under different collectors and compare the tail), CPU affinity, and tail-tuning notes.
//
// The headline app is LatencyBenchmarkApp; the registered `bench*` tasks below launch it under
// different garbage collectors so you can compare pauses and tail latency apples-to-apples.
plugins {
    application
}

dependencies {
    implementation(project(":engine-core"))
    implementation(project(":engine-disruptor"))
    implementation(libs.hdrhistogram)
    implementation(libs.disruptor)
    implementation(libs.affinity)   // best-effort CPU pinning (real on Linux, no-op elsewhere)
    implementation(libs.jna)          // override affinity's ancient JNA 5.5.0 (no arm64 native)
    implementation(libs.jna.platform)
    // affinity logs via SLF4J; bind a no-op provider so the benchmark output isn't polluted by the
    // "Failed to load class StaticLoggerBinder" warning. We deliberately don't want logging here.
    runtimeOnly("org.slf4j:slf4j-nop:1.7.32")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("com.lowlatency.tuning.LatencyBenchmarkApp")
}

// `./gradlew :tuning:run` forks a fresh JVM, so `-Dbench.*` flags given on the command line land on the
// Gradle JVM, not the app. Forward them through explicitly so the documented overrides actually work,
// e.g. `./gradlew :tuning:run -Dbench.matrix=false -Dbench.waitStrategy=busyspin -Dbench.pinCpu=true`.
tasks.named<JavaExec>("run") {
    System.getProperties().forEach { key, value ->
        if (key.toString().startsWith("bench.")) {
            systemProperty(key.toString(), value.toString())
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// GC experiments. Each task runs the SAME single-strategy workload (busy-spin) under a different
// collector, so the percentiles and GC-pause lines printed at the end are directly comparable.
//   ./gradlew :tuning:benchG1        # the default server collector
//   ./gradlew :tuning:benchZgc       # concurrent, sub-millisecond pauses
//   ./gradlew :tuning:benchParallel  # throughput collector (longer, rarer pauses)
//   ./gradlew :tuning:benchEpsilon   # no-op GC: proves the hot path allocates ~nothing
// Shenandoah is intentionally absent — it ships in OpenJDK/Temurin builds but NOT in the Oracle JDK,
// so we don't register a task that would fail on this machine. The doc explains how to add it.
fun registerBench(name: String, gcLabel: String, jvm: List<String>) =
    tasks.register<JavaExec>(name) {
        group = "benchmark"
        description = "End-to-end latency benchmark under $gcLabel."
        mainClass.set("com.lowlatency.tuning.LatencyBenchmarkApp")
        classpath = sourceSets["main"].runtimeClasspath
        systemProperty("bench.matrix", "false")
        systemProperty("bench.waitStrategy", "busyspin")
        systemProperty("bench.gcLabel", gcLabel)
        jvmArgs = jvm
    }

registerBench("benchG1", "G1 (default)", listOf("-Xms2g", "-Xmx2g", "-XX:+UseG1GC"))
registerBench("benchZgc", "ZGC (generational)", listOf("-Xms2g", "-Xmx2g", "-XX:+UseZGC", "-XX:+ZGenerational"))
registerBench("benchParallel", "Parallel", listOf("-Xms2g", "-Xmx2g", "-XX:+UseParallelGC"))
// Epsilon never reclaims, so a generous heap is mandatory — if the workload allocated steadily it
// would OOM. It completing is the proof the matching path is allocation-free.
registerBench("benchEpsilon", "Epsilon (no-op)",
    listOf("-Xms2g", "-Xmx2g", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseEpsilonGC"))
