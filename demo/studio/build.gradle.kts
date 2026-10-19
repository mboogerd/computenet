plugins {
    id("buildsrc.convention.ksp-cell")
    alias(libs.plugins.kotlin.plugin.serialization)
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))
    implementation(libs.kotlinx.serialization)
    implementation(libs.networknt) {
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-yaml")
    }
    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.demo.studio.StudioAppKt"
}
