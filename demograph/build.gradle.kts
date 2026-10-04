plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.ksp-cell")
}

// The direction is :demograph -> :kernel only; :kernel MUST NOT depend on
// :demograph (computenet-drz8, "same dependency rule as :identity"). `:nature`
// is not declared explicitly: `:kernel` already exposes it as `api(:nature)`
// (kernel/build.gradle.kts), so it reaches this module's compile classpath
// transitively and a second, redundant declaration would only invite the two
// to drift. The cell-authoring convention adds `ksp(project(":gen"))` inside
// the plugin, processor-time only, so the dependency fence (`api(:kernel)`
// only) remains unchanged.
dependencies {
    api(project(":kernel"))

    testImplementation(kotlin("test"))
}
