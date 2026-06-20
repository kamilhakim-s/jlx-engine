// Multi-module Gradle build. New modules get added to this include list as the
// project grows across chunks (engine-core, engine-disruptor, market-data, ...).
plugins {
    // Lets Gradle auto-provision a Java 21 toolchain if the local JDK differs.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "low-latency"

include(":engine-core")
include(":engine-disruptor")
include(":journal")
include(":market-data")
include(":streaming")
include(":benchmarks")
include(":tuning")
include(":gateway")
