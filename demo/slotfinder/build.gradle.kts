plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.platform)
    testImplementation(kotlin("test"))
    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.demo.slotfinder.SlotFinderAppKt"
}
