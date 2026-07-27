plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
}

// :inspect is the Inspector backend (doc/spec/90-roadmap/97-inspector-plan):
// a read-only HTTP/SSE view of a host process's live dataflow graph. It reuses
// :demo:shell's JDK-httpserver + SSE framing rather than duplicating it, and
// adds no third-party dependency beyond the kotlinx.serialization the kernel
// already uses. The frontend (inspect/ui) is npm/Vite and is deliberately not
// wired into Gradle — same decision as demo/agora/ui.
dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))
    implementation(libs.kotlinx.serialization)

    // the shared JUnit5/kotest/kotlin-test stack comes from the kotlin-jvm convention
    testImplementation(project(":testkit"))
}
