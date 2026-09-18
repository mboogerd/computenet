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
