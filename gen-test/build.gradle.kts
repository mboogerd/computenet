plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.ksp)
}

group = "civictech"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.kotlinx.coroutines)
    testImplementation(project(":gen"))
    ksp(project(":gen"))
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly(libs.junit.platform)
}

tasks.test {
    useJUnitPlatform()
    // ponytail: this module has no @Tests — it exists to run KSP codegen and prove it
    // compiles (kernel depends on it). Gradle 9 fails empty test tasks; opt out.
    failOnNoDiscoveredTests = false
}