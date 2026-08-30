plugins {
    id("buildsrc.convention.kotlin-jvm")
    // The v1 spend-record model (computenet-fpml.1.1) is decoded from JSONL
    // lines with kotlinx.serialization's Json, same pairing as :demo:agora
    // and :demo:beadsmirror.
    alias(libs.plugins.kotlin.plugin.serialization)
}

// :demo:allocator-observe ingests the socaity-owned JSONL spend log (epic
// computenet-fpml) into kernel cells and will later serve derived views over
// :demo:shell's HTTP/SSE plumbing (F4, not yet implemented). Feature
// computenet-fpml.1 has landed the v1 SpendRecord model and its per-line
// classifier (1.1), the checkpointed tail reader (1.2), and the
// SpendLogIngester that folds records into a kernel SetCell (1.3). Serving and
// an application entry point belong to F4, so there is still deliberately no
// `application` block here.
dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))
    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization)

    testImplementation(project(":testkit"))
}
