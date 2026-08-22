plugins {
    id("buildsrc.convention.kotlin-jvm")
    // dialogue's §2.3 data types (Utterance, Segment, CanonicalClaim,
    // CanonicalRelation, ProjectedStance) carry
    // `@kotlinx.serialization.Serializable` so JSONL fixtures and any future
    // HTTP/SSE payloads round-trip — same pairing as `:demo:agora` and
    // `:demo:beadsmirror`.
    alias(libs.plugins.kotlin.plugin.serialization)
    application
}

// :demo:dialogue is AGO1's argumentation-extraction pipeline (epic
// computenet-2aw): it ingests recorded dialogue transcripts and derives
// canonical claims/relations/stances. It depends on :kernel for the
// cell-model types the pipeline is built from, :demo:shell for the HTTP/SSE
// plumbing a later feature serves the pipeline through, and :demo:agora to
// reuse its existing claim/edge vocabulary (Polarity) rather than minting a
// parallel one.
dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))
    implementation(project(":demo:agora"))
    implementation(libs.kotlinx.serialization)

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.dialogue.DialogueAppKt"
}
