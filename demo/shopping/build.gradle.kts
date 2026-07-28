plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":wire"))
    implementation(project(":demo:shell"))
    // pilot host for the inspector's network-host vertical (97-inspector-plan
    // M5-NET): `--inspect-port` serves this JVM's live graph — its own cells
    // and the peer's — on a second port. Opt-in, default off.
    implementation(project(":inspect"))

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.demo.MainKt"
}
