plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

// :demo:beadsmirror mirrors a bd/Dolt-backed beads workspace; it depends on
// :kernel for the cell-model types the projector feature builds on and on
// :demo:shell for the HTTP/SSE plumbing the mirror serves the materialized
// fold through. computenet-dqj.4.2 adds the runnable main (reader ->
// projector -> shell against a --workspace path).
dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))
    implementation(libs.kotlinx.serialization)

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.demo.beadsmirror.BeadsMirrorAppKt"
}
