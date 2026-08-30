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
    // computenet-dqy.25: `--listen 0` lets this JVM's peering listener pick its own
    // port and `TieringApp.boundWsPort` reads back which one it got — that accessor is
    // `WsTransport.WsListener`'s inherited `WebSocketServer.getPort()`. `:wire`
    // declares java-websocket as `implementation` (deliberately — `:kernel` stays
    // transport-free), so the class reaches this module's runtime classpath
    // transitively but not its compile classpath: needs stating here.
    implementation(libs.java.websocket)
    implementation(libs.kotlinx.serialization)
    implementation(project(":demo:shell"))

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.demo.tiering.TieringAppKt"
}
