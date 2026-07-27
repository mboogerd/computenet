plugins {
    id("buildsrc.convention.ksp-cell")
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(libs.kotlinx.serialization)
    implementation(project(":demo:shell"))

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.demo.backlogtriage.TriageAppKt"
}
