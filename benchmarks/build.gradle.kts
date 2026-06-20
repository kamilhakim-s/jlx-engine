// The `benchmarks` module: our measurement harness. It hosts two things:
//   1. JMH microbenchmarks (src/jmh/java) — for nanosecond-level throughput/latency
//      of small code units, run via `./gradlew :benchmarks:jmh`.
//   2. Runnable demos (src/main/java) — full programs that teach a concept, run via
//      `./gradlew :benchmarks:run`.
//
// In low-latency work the harness comes BEFORE the system under test: you cannot
// optimise what you cannot measure, and naive measurement lies (see CoordinatedOmissionDemo).

plugins {
    application
    alias(libs.plugins.jmh)
}

dependencies {
    implementation(libs.hdrhistogram)
    implementation(project(":engine-core")) // benchmark the matching engines
}

application {
    // The Chunk 0 demo. Override with -PmainClass or change here as we add demos.
    mainClass.set("com.lowlatency.bench.CoordinatedOmissionDemo")
}

jmh {
    // Keep iterations modest so a full run finishes quickly while learning.
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)
    // The GC profiler reports "·gc.alloc.rate.norm" = bytes allocated per op. This is how we
    // prove the Chunk 2 match path is ~0 B/op versus the Chunk 1 engine's per-trade garbage.
    profilers.add("gc")
}
