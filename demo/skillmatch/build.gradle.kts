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
    // query-equivalence tests (computenet-cab.7, cab.7-D9): the skillmatch
    // query and its structural/extensional comparisons live in the TEST source
    // set — `:query`'s own ModuleDependencyTest forbids the reverse direction.
    testImplementation(project(":query"))
    testImplementation(project(":oracle"))
}

application {
    mainClass = "civictech.demo.skillmatch.SkillMatchAppKt"
}
