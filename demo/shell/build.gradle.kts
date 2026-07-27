plugins {
    id("buildsrc.convention.kotlin-jvm")
}

// :demo:shell is consumed as `implementation` by the demo modules — the JDK
// httpserver + SSE boilerplate duplicated verbatim across their mains
// (see doc/archive/runs/RESTRUCTURE-PLAN.md RS-9.3/9.4). DemoShell itself is transport
// plumbing with no cell-model types in its API today, so no :kernel dependency
// here; the module boundary matches every other demo, and the day DemoShell's
// API does take a cell-model type, `implementation(project(":kernel"))` returns.
//
// kotlinx-serialization-json (T12): backs the shared `esc` JSON-string
// escaper (JsonPrimitive.toString()) — an `implementation` dependency since
// esc's own signature stays plain String, no serialization type crosses
// this module's public API.
dependencies {
    implementation(libs.kotlinx.serialization)
}
