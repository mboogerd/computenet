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
    // civictech.gen.wire.{Contract,Key,Protocol,CellBase,ProxyRegistry} — the
    // annotations cell/port authors apply and the generated-proxy lookup — live in
    // :nature (T09 §A), reachable via the api dependency above. ksp-cell's
    // `ksp(project(":gen"))` is processor-time only: :gen (KotlinPoet, KSP's
    // symbol-processing-api, kotlin-reflect) never lands on kernel's classpath.

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