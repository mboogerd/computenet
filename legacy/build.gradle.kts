// Quarantined pre-germ generation (G-1): kept compiling as reference for the
// M3 color-model port, scheduled for deletion afterwards. Nothing depends on it.
plugins {
    id("buildsrc.convention.kotlin-jvm")
}
dependencies {
    implementation(libs.kotlinx.coroutines)

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.platform)
    testImplementation(kotlin("test"))
}
