plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":wire"))
    implementation(project(":nature")) // PN-15: the Manifest nature vocabulary for the composed-manifest assertion
    implementation(project(":demo:shell"))
    implementation(project(":inspect")) // computenet-3iv0w.4: the shared `--inspect-port` opt-in (InspectorFlag)

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.demo.exchange.MainKt"
}
