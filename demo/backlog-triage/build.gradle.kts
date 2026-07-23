plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(libs.kotlinx.serialization)

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.platform)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "civictech.demo.backlogtriage.TriageAppKt"
}
