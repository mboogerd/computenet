plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.ksp)
}
dependencies {
    implementation(libs.kotlinx.coroutines)
    implementation(project(":gen"))
    ksp(project(":gen"))
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