plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
}

dependencies {
    // The kernel binding (civictech.concord.driver.kernel) lands in W1-A; the
    // neutral SPI/check/schema packages must not import civictech.cell.* even
    // though the module depends on :kernel.
    implementation(project(":kernel"))
    // @Serializable data classes in main (schema + value model).
    implementation(libs.kotlinx.serialization)

    // kaml is the YAML front end. All scenario parsing happens in the test
    // source set (the runner is a JUnit harness — W1-A), so kaml is test-scope.
    // 0.77.1 is built against kotlinx-serialization 1.9.0 (the version this repo
    // pins) and resolves cleanly under Kotlin 2.1.21.
    testImplementation("com.charleskorn.kaml:kaml:0.77.1")

    // Same test stack the kernel uses.
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.platform)
    testImplementation(kotlin("test"))
}
