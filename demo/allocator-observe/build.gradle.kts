plugins {
    id("buildsrc.convention.kotlin-jvm")
    // The v1 spend-record model (computenet-fpml.1.1) is decoded from JSONL
    // lines with kotlinx.serialization's Json, same pairing as :demo:agora
    // and :demo:beadsmirror.
    alias(libs.plugins.kotlin.plugin.serialization)
    application
}

// :demo:allocator-observe ingests the socaity-owned JSONL spend log (epic
// computenet-fpml) into kernel cells and serves the derived views over
// :demo:shell's HTTP/SSE plumbing. Feature computenet-fpml.1 lands the ingest
// half (v1 SpendRecord model and per-line classifier, checkpointed tail
// reader, SetCell-folding SpendLogIngester); computenet-fpml.2 the declaration
// history; computenet-fpml.3 the derived R5/R6 report views; and
// computenet-fpml.4 the serving half — AllocatorObserveApp's poll driver, the
// read-only /state routes and the /events SSE stream — which is what the
// `application` block below makes runnable.
dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))
    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization)

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.demo.allocatorobserve.AllocatorObserveAppKt"
}

// F5's external-oracle channel (computenet-fpml.5.4, fpml.5-D7):
// `oracle/ExternalOracleComparisonTest` reads its four inputs plus the
// optional window length from system properties, but `-D` on the
// `./gradlew` command line reaches Gradle's own JVM, never the test JVM —
// only `-P` project properties (read here via `providers.gradleProperty`,
// evaluated at configuration time) survive to invoke a task at all, so each
// property has to be forwarded explicitly. Same idiom `wire/build.gradle.kts`
// uses for its own `-D`-to-test-JVM channel, adapted to `-P` because that is
// what actually reaches a Gradle command line's test run.
val forwardedOracleProperties = listOf(
    "allocator.oracle.report",
    "allocator.oracle.log",
    "allocator.oracle.declarations",
    "allocator.oracle.now",
    "allocator.oracle.windowHours",
)

tasks.withType<Test>().configureEach {
    forwardedOracleProperties.forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
