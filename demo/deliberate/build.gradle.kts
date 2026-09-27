plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))
    implementation(project(":demo:agora"))
    implementation(libs.kotlinx.serialization)

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.deliberate.DeliberateAppKt"
}
