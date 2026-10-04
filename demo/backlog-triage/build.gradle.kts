plugins {
    id("buildsrc.convention.ksp-cell")
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":demograph")) // computenet-drz8.2: shared ranking types and engines
    implementation(libs.kotlinx.serialization)
    implementation(project(":demo:shell"))
    implementation(project(":inspect")) // computenet-3iv0w.5: the shared `--inspect-port` opt-in (InspectorFlag)

    testImplementation(project(":testkit"))
    // computenet-cab.7.9 ([QRY1-ORA-10]): backlog-triage's mean-lane relational core expressed
    // as a query (TriageQuery.kt) and checked for extensional agreement with the hand-wired
    // TriagePipeline through :oracle's DifferentialRunner (cab.7-D9: :query forbids a
    // :demo:* dependency even in test scope, so the query and its test live here, not
    // in :query's own test source set).
    testImplementation(project(":query"))
    testImplementation(project(":oracle"))
}

application {
    mainClass = "civictech.demo.backlogtriage.TriageAppKt"
}
