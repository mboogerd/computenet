plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))
    // pilot host for the inspector (97-inspector-plan M0): `--inspect-port`
    // serves this demo's live graph on a second port. Opt-in, default off.
    implementation(project(":inspect"))

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.demo.skillmatch.SkillMatchAppKt"
}
