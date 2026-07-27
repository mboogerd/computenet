plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts` and
    // `buildSrc/src/main/kotlin/ksp-cell.gradle.kts`.
    id("buildsrc.convention.ksp-cell")
    alias(libs.plugins.kotlin.plugin.serialization)
}
dependencies {
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization)
    api(project(":nature"))
    // Beyond ksp-cell's implementation/ksp(project(":gen")): kernel cell/port authors
    // apply the @Contract/@CellBase/@Key/@Protocol annotations (processor input,
    // stays in :gen) and civictech.gen.wire.ProxyRegistry (generated-proxy lookup).

    testImplementation(project(":testkit"))
}

// :gen's own test suite (ContractProcessorTest, NatureDescriptorSweepTest) is the
// real generator-regression gate; wiring it ahead of compileKotlin makes
// doc/ARCHITECTURE.md's "generator regressions fail before kernel compiles" claim
// true (the deleted :gen-test module was a verified no-op: zero sources, NO-SOURCE
// on every task).
tasks.named("compileKotlin") {
    dependsOn(project(":gen").tasks.named("test"))
}