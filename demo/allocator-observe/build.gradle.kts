plugins {
    id("buildsrc.convention.kotlin-jvm")
    // The v1 spend-record model (computenet-fpml.1.1) is decoded from JSONL
    // lines with kotlinx.serialization's Json, same pairing as :demo:agora
    // and :demo:beadsmirror.
    alias(libs.plugins.kotlin.plugin.serialization)
}

// :demo:allocator-observe ingests the socaity-owned JSONL spend log (epic
// computenet-fpml) into kernel cells and will later serve derived views over
// :demo:shell's HTTP/SSE plumbing (F4, not yet implemented). This task
// (computenet-fpml.1.1) only adds the module scaffold and the v1
// SpendRecord model plus its per-line classifier — no ingest, no cells, no
// application entry point yet, so there is deliberately no `application`
// block here.
dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))
    implementation(libs.kotlinx.serialization)

    testImplementation(project(":testkit"))
}
