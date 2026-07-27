package buildsrc.convention

// Shared by every module that authors @Contract/@CellBase/@Key/@Protocol cells and
// needs :gen's KSP processor to run over them: the KSP plugin, ksp(project(":gen"))
// (processor-time only), and the generated-source dir wiring. Applies on top of
// `buildsrc.convention.kotlin-jvm`.
plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("com.google.devtools.ksp")
}

// T09 §A: no `implementation(project(":gen"))` here. The runtime vocabulary a cell
// author actually needs at compile+runtime — @Contract/@CellBase/@Key/@Protocol and
// civictech.gen.wire.ProxyRegistry — lives in :nature (see Contract.kt/CellBase.kt/
// ProxyRegistry.kt there), reachable transitively through `:kernel`'s
// `api(project(":nature"))`. Depending on `:gen` here would drag KotlinPoet,
// symbol-processing-api, and kotlin-reflect (the processor's own dependencies) onto
// every consumer's runtime classpath for four symbols worth of annotations.
dependencies {
    "ksp"(project(":gen"))
}

kotlin {
    sourceSets {
        named("main") {
            kotlin.srcDir("build/generated/ksp/main/kotlin")
        }
    }
}
