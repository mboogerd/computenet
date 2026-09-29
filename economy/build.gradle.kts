plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    // `EconomicPolicy` and its nested types carry `@kotlinx.serialization.Serializable`
    // and need the plugin that generates their serializers — the same pairing
    // `kernel/build.gradle.kts` and `demo/tiering/build.gradle.kts` use
    // (`[ECO1-POL-04]`).
    alias(libs.plugins.kotlin.plugin.serialization)
}

// The direction is :economy -> :kernel only; :kernel MUST NOT depend on :economy
// (epic computenet-66m decision 66m-D3, "same dependency rule as :identity"/:demograph).
// `:nature` is not declared explicitly: `:kernel` already exposes it as `api(:nature)`
// (kernel/build.gradle.kts), so it reaches this module's compile classpath transitively,
// and a second, redundant declaration would only invite the two to drift.
dependencies {
    api(project(":kernel"))
    implementation(libs.kotlinx.serialization)

    testImplementation(kotlin("test"))
}
