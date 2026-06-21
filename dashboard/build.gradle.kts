// dashboard: Chunk 10 — a live web dashboard that turns the engine's invisible nanosecond work into a
// product. It attaches PAST the AsyncTradeForwarder seam (never touches the hot path) and pushes JSON
// frames — latency percentiles, throughput, GC/allocation, candles, trades — to a React/Vite SPA over
// Server-Sent Events. Backend transport is the JDK's own HttpServer; no heavyweight web framework.
plugins {
    application
}

dependencies {
    implementation(project(":engine-disruptor"))   // DisruptorMatchingService (+ SingleWriterRecorder via api)
    implementation(project(":market-data"))         // BinanceLiveClient, MarketDataReplayer, AggTrade
    implementation(project(":tuning"))              // GcStats, AllocationCounter
    implementation(libs.hdrhistogram)               // SingleWriterRecorder, Histogram
    implementation(libs.jackson.databind)           // frame serialization
    implementation(libs.disruptor)                   // ProducerType.MULTI (live feed + load generator both publish)

    // Reuse Chunk 6's seam + analytics maths (plain Java), but exclude the Flink/Kafka frameworks — the
    // dashboard runs everything in process and they'd re-trigger Chunk 6's lz4 capability clash (see :app).
    implementation(project(":streaming")) {
        exclude(group = "org.apache.flink")
        exclude(group = "org.apache.kafka")
    }
    // Transitive deps (Chunk 7's affinity) drag SLF4J; api resolves to 2.0.x — bind a matching no-op
    // provider so the console isn't polluted by "no providers / ignored binding" warnings.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("com.lowlatency.dashboard.DashboardApp")
}

// ---------------------------------------------------------------------------------------------------
// Frontend packaging. The React/Vite app lives in ../dashboard-web. `:dashboard:buildWeb` builds it and
// mirrors the output into this module's resources, so the Java server serves the SPA at the root and
// `./gradlew :dashboard:run` works out of the box. (The default `build`/`run` do NOT auto-run npm, so a
// node-less machine can still compile and test the Java; run buildWeb once to refresh the UI.)
val webDir = rootProject.layout.projectDirectory.dir("dashboard-web")

val viteBuild = tasks.register<Exec>("viteBuild") {
    group = "frontend"
    description = "npm install + vite build for the dashboard SPA."
    workingDir = webDir.asFile
    commandLine("sh", "-c", "npm install --no-audit --no-fund && npm run build")
}

tasks.register<Sync>("buildWeb") {
    group = "frontend"
    description = "Build the SPA and mirror it into src/main/resources/web (served by the backend)."
    dependsOn(viteBuild)
    from(webDir.dir("dist"))
    into(layout.projectDirectory.dir("src/main/resources/web"))
}

// Capture a recorded session into the SPA's public/ for the GitHub Pages demo (replayed when VITE_DEMO=1).
tasks.register<JavaExec>("recordDemo") {
    group = "frontend"
    description = "Record a live dashboard session to dashboard-web/public/demo-frames.ndjson."
    mainClass.set("com.lowlatency.dashboard.DemoRecorder")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    args("dashboard-web/public/demo-frames.ndjson", "45")
}
