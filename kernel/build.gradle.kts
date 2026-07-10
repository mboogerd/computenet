plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
}
dependencies {
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization)
    implementation(project(":gen"))
    ksp(project(":gen"))

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.platform)
    testImplementation(kotlin("test"))
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDir("build/generated/ksp/main/kotlin")
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn(project(":gen-test").tasks.named("test"))
}