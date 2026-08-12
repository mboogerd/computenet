plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":wire"))
    // computenet-dqy.25: `--listen 0` lets this JVM's peering listener pick its own
    // port and `DemoApp.boundWsPort` reads back which one it got — that accessor is
    // `WsTransport.WsListener`'s inherited `WebSocketServer.getPort()`. `:wire`
    // declares java-websocket as `implementation` (deliberately — `:kernel` stays
    // transport-free), so the class reaches this module's runtime classpath
    // transitively but not its compile classpath: needs stating here, exactly as
    // kotlinx.serialization does below for `:inspect`'s DTOs.
    implementation(libs.java.websocket)
    implementation(project(":demo:shell"))
    // pilot host for the inspector's network-host vertical (97-inspector-plan
    // M5-NET): `--inspect-port` serves this JVM's live graph — its own cells
    // and the peer's — on a second port. Opt-in, default off.
    implementation(project(":inspect"))

    testImplementation(project(":testkit"))
    // T22: decode :inspect's TopologySnapshot/Node DTOs directly in
    // TwoJvmInspectorTest. :inspect declares this as `implementation`
    // (not `api`), so it reaches this module's runtime classpath
    // transitively but not its (test) compile classpath — needs stating here.
    testImplementation(libs.kotlinx.serialization)
}

application {
    mainClass = "civictech.demo.MainKt"
}
