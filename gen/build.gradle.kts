plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(libs.ksp)
    implementation(libs.kotlin.poet)
    implementation(libs.kotlin.poet.ksp)
    testImplementation(kotlin("test"))
}