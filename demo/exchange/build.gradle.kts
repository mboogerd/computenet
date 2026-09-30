plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":wire"))
    // computenet-dqy.25: `--listen 0` lets this JVM's peering listener pick its own
    // port and `ExchangeApp.boundWsPort` reads back which one it got — that accessor
    // is `WsTransport.WsListener`'s inherited `WebSocketServer.getPort()`. `:wire`
    // declares java-websocket as `implementation` (deliberately — `:kernel` stays
    // transport-free), so the class reaches this module's runtime classpath
    // transitively but not its compile classpath: needs stating here.
    implementation(libs.java.websocket)
    implementation(project(":nature")) // PN-15: the Manifest nature vocabulary for the composed-manifest assertion
    implementation(project(":demo:shell"))
    implementation(project(":inspect")) // computenet-3iv0w.4: the shared `--inspect-port` opt-in (InspectorFlag)

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.demo.exchange.MainKt"
}
