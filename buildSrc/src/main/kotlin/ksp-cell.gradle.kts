package buildsrc.convention

// Shared by every module that authors @Contract/@CellBase/@Key/@Protocol cells and
// needs :gen's KSP processor to run over them: the KSP plugin, the implementation +
// ksp(project(":gen")) pair, and the generated-source dir wiring. Applies on top of
// `buildsrc.convention.kotlin-jvm`.
plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("com.google.devtools.ksp")
}

dependencies {
    "implementation"(project(":gen"))
    "ksp"(project(":gen"))
}

kotlin {
    sourceSets {
        named("main") {
            kotlin.srcDir("build/generated/ksp/main/kotlin")
        }
    }
}
