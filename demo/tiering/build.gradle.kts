plugins {
    id("buildsrc.convention.kotlin-jvm")
    // computenet-3san: `Valuation`/`Pref` and `TieringWireSerializers`'s
    // registrations carry `@kotlinx.serialization.Serializable`/`@SerialName`
    // and need the plugin that generates their serializers — same pairing as
    // `:demo:agora` and `:demo:beadsmirror`.
    alias(libs.plugins.kotlin.plugin.serialization)
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":wire"))
    implementation(libs.kotlinx.serialization)
    implementation(project(":demo:shell"))
    implementation(project(":inspect")) // computenet-3iv0w.4: the shared `--inspect-port` opt-in (InspectorFlag)

    testImplementation(project(":testkit"))
    // computenet-cab.7.8 ([QRY1-ORA-10]): tiering's relational core expressed as a query
    // ( TieringQuery.kt) and checked for extensional agreement with the hand-wired
    // TierPipeline through :oracle's DifferentialRunner (cab.7-D9: :query forbids a
    // :demo:* dependency even in test scope, so the query and its test live here, not
    // in :query's own test source set).
    testImplementation(project(":query"))
    testImplementation(project(":oracle"))
}

application {
    mainClass = "civictech.demo.tiering.TieringAppKt"
}
