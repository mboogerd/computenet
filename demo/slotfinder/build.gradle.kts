plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))

    testImplementation(project(":testkit"))
    testImplementation(project(":query"))
    testImplementation(project(":oracle"))
}

application {
    mainClass = "civictech.demo.slotfinder.SlotFinderAppKt"
}
