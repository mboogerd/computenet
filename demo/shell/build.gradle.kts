plugins {
    id("buildsrc.convention.kotlin-jvm")
}

// :demo:shell is consumed as `implementation` by the demo modules — the JDK
// httpserver + SSE boilerplate duplicated verbatim across their mains
// (see doc/RESTRUCTURE-PLAN.md RS-9.3/9.4). It depends on :kernel only, per
// that plan; DemoShell itself is transport plumbing with no cell-model types
// in its API today, but the module boundary matches every other demo.
dependencies {
    implementation(project(":kernel"))

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.platform)
    testImplementation(kotlin("test"))
}
