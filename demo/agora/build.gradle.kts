plugins {
    // agora defines no @Contract/@CellBase cells, but applies ksp-cell anyway
    // (computenet-jnkvu R5): :gen's ContractProcessor emits a `<CellName>Ports`
    // object (typed InletId/OutletId ids) for every non-private Cell subclass
    // it scans, annotation or not — ClaimCellPorts/EdgeCellPorts are what the
    // routed hops below resolve against. ksp-cell.gradle.kts applies
    // kotlin-jvm itself.
    id("buildsrc.convention.ksp-cell")
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
    // Test-scope consumer only (TimeTravelWalkthroughTest, computenet-3qkx1.3) — the same shape
    // as the demo modules' `:query` test dependency; :timetravel stays a main-scope leaf [TTD1-53].
    testImplementation(project(":timetravel"))

    testFixturesImplementation(project(":kernel"))
}

application {
    mainClass = "civictech.agora.AgoraAppKt"
}
