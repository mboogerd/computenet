plugins {
    id("buildsrc.convention.ksp-cell")
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
