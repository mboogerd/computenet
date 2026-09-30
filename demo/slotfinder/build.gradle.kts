plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))
    implementation(project(":inspect")) // computenet-3iv0w.5: the shared `--inspect-port` opt-in (InspectorFlag)

    testImplementation(project(":testkit"))
    testImplementation(project(":query"))
    testImplementation(project(":oracle"))
}

application {
    mainClass = "civictech.demo.slotfinder.SlotFinderAppKt"
}
