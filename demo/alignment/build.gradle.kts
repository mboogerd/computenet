plugins {
    id("buildsrc.convention.ksp-cell")
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(libs.kotlinx.serialization)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.java) // engine over the JDK HttpClient: no extra network stack
    implementation(project(":demo:shell"))
    implementation(project(":inspect")) // computenet-3iv0w.5: the shared `--inspect-port` opt-in (InspectorFlag)

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.demo.alignment.AlignmentAppKt"
}
