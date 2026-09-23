plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    application
}

// :timetravel is offline, headless time-travel tooling over durability journals —
// reader, reconstruction, diff, and a CLI (TTD1, epic computenet-ocv). It is a leaf
// on :kernel by design (epic §2 "Module"): nothing else depends on it until TTD2
// wires it into :inspect, and it must never depend on :inspect, :wire, :concord,
// :identity, :iroh, :oracle, :query or any :demo:* module — its own
// ModuleDependencyTest enforces that.
application { mainClass = "civictech.timetravel.cli.MainKt" }

dependencies {
    implementation(project(":kernel"))
    implementation(libs.kotlinx.serialization)

    // the shared JUnit5/kotest/kotlin-test stack comes from the kotlin-jvm convention
    testImplementation(project(":testkit"))
}
