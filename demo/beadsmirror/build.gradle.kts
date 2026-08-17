plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

// :demo:beadsmirror mirrors a bd/Dolt-backed beads workspace; it depends on
// :kernel for the cell-model types the projector feature builds on and on
// :demo:shell for the HTTP/SSE plumbing the mirror serves the materialized
// fold through. computenet-dqj.4.2 adds the runnable main (reader ->
// projector -> shell against a --workspace path). computenet-7em.1.2 adds
// :wire for the opt-in two-node mode, in which the projector's two cells
// gossip their deltas to one peer over the real WebSocket transport
// (civictech.demo.beadsmirror.MirrorPeering — the only file in the module that
// names a :wire type, so a solo run loads none of it).
dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))
    implementation(project(":wire"))
    // `--listen 0` lets this node pick its own port and `MirrorPeering.boundWsPort`
    // reads back which one it got (computenet-dqy.25) — that accessor is
    // `WsTransport.WsListener`'s inherited `WebSocketServer.getPort()`, and
    // `close()` is `WsConnection`'s inherited `WebSocketClient.close()`. `:wire`
    // declares java-websocket as `implementation` (deliberately — `:kernel` stays
    // transport-free), so those supertypes reach this module's runtime classpath
    // transitively but not its compile classpath: needs stating here, exactly as
    // demo/shopping does for the same two accessors.
    implementation(libs.java.websocket)
    implementation(libs.kotlinx.serialization)

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.demo.beadsmirror.BeadsMirrorAppKt"
}
