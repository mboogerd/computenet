plugins {
    // agora defines no @Contract/@CellBase cells (T09 §C) — plain kotlin-jvm,
    // not ksp-cell. Re-add ksp-cell the day it annotates something.
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    application
    // First use of this plugin in the repo (computenet-5swa) — verified via
    // `grep -rn 'java-test-fixtures\|testFixtures(' --include='*.kts' .` before
    // adding it. Exposes `BatchReference`, the batch fixpoint solver AGO1's
    // differential tests check the incremental path against, to consuming
    // modules without exposing this module's whole test source set (which also
    // carries the `Harness` SimWorld wrapper, not meant for reuse). The
    // `testFixtures` source set gets a compile dependency on `main`
    // automatically, and this module's own `test` source set gets one on
    // `testFixtures` automatically too — see
    // src/testFixtures/kotlin/civictech/agora/BatchReference.kt.
    id("java-test-fixtures")
}

dependencies {
    implementation(project(":kernel"))
    implementation(libs.kotlinx.serialization)
    implementation(project(":demo:shell"))

    testImplementation(project(":testkit"))

    testFixturesImplementation(project(":kernel"))
}

application {
    mainClass = "civictech.agora.AgoraAppKt"
}
