// journal: durably records the inbound command stream to a Chronicle Queue and rebuilds engine state
// by deterministic replay (event sourcing). Runs as a parallel Disruptor consumer alongside the
// matching engine, so journaling stays off the matching critical path.
plugins {
    `java-library`
    application
}

dependencies {
    api(project(":engine-disruptor"))   // OrderCommand, DisruptorMatchingService (brings engine-core)
    implementation(libs.disruptor)      // implement EventHandler<OrderCommand>
    implementation(libs.chronicle.queue)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("com.lowlatency.journal.JournalDemo")
}

// Chronicle reaches into JDK internals for memory-mapped files and cleaners; on Java 17+ these
// module flags are required to avoid InaccessibleObjectException at runtime.
val chronicleJvmArgs = listOf(
    "-Dchronicle.analytics.disable=true", // no phone-home / analytics client-id file
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
