plugins {
    // agora defines no @Contract/@CellBase cells (T09 §C) — plain kotlin-jvm,
    // not ksp-cell. Re-add ksp-cell the day it annotates something.
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(libs.kotlinx.serialization)
    implementation(project(":demo:shell"))

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.agora.AgoraAppKt"
}
