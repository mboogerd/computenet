plugins {
    id("buildsrc.convention.kotlin-jvm")
}

// :demo:beadsmirror mirrors a bd/Dolt-backed beads workspace; it depends on
// :kernel for the cell-model types the projector feature builds on and on
// :demo:shell for the HTTP/SSE plumbing a later feature serves the
// materialized fold through. No main class yet (computenet-dqj.1.1 is the
// scaffold + Dolt access layer only), so no `application` block.
dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))
    implementation(libs.kotlinx.serialization)

    testImplementation(project(":testkit"))
}
