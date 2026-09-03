plugins {
    id("buildsrc.convention.kotlin-jvm")
    // computenet-7em.1.5: the mirror's own OR-map key and dependency-edge
    // element cross `:wire` inside kernel deltas, so they carry
    // `@kotlinx.serialization.Serializable` and need the plugin that generates
    // their serializers — same pairing as `:demo:agora`.
    alias(libs.plugins.kotlin.plugin.serialization)
    application
}

// :demo:beadsmirror mirrors a bd/Dolt-backed beads workspace; it depends on
// :kernel for the cell-model types the projector feature builds on and on
// :demo:shell for the HTTP/SSE plumbing the mirror serves the materialized
// fold through. computenet-dqj.4.2 adds the runnable main (reader ->
// projector -> shell against a --workspace path). computenet-7em.1.2 adds
// :wire for the opt-in two-node mode, in which the projector's two cells
// gossip their deltas to one peer over the real WebSocket transport
// (civictech.demo.beadsmirror.MirrorTransport's WsMirrorTransport binding —
// the only file in the module that names a :wire type, so a solo run loads
// none of it; MirrorPeering itself only names civictech.cell.wire.Peering, a
// :kernel type despite the package name — corrected, computenet-mwwr).
dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))
    implementation(project(":wire"))
    // computenet-egl.4.1: the second MirrorTransport binding
    // (IrohMirrorTransport), which carries the same peering over an iroh QUIC
    // link. This does NOT put cargo on the default compile path: :iroh
    // registers its cargo tasks only inside `if
    // (project.hasProperty("iroh.enabled"))`, so on the unset path :iroh is an
    // ordinary pure-JVM module and this dependency costs a Kotlin compile.
    implementation(project(":iroh"))
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

// NO `tasks.test { }` BLOCK HERE, deliberately (computenet-9vx3). This module runs
// its tests on PARALLEL forks — the only module besides `:kernel` that does — but
// the setting lives with `:kernel`'s in
// `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`, next to the measurements and
// the port/statics audit that justify it, so the two fork policies can be read
// against each other instead of drifting apart in separate files. Look there
// before changing anything about this module's test concurrency, and in
// particular before adding a test that binds a port by any means other than
// `bind(0)`-and-keep-it.

// computenet-egl.4.1: the sidecar-locating wiring for this module's
// iroh-backed tests, guarded by the SAME project property iroh/build.gradle.kts
// guards its cargo tasks with. The guard is load-bearing, not cosmetic: the
// ":iroh:cargoBuild" task does not EXIST outside it, so an unguarded reference
// fails at configuration time on every default build. On the unset path this
// block contributes nothing and the module's build is byte-for-byte what it
// was before — no cargo, no system property, and every iroh-backed test here
// takes IrohSidecarGate's JUnit assumption and reports SKIPPED.
//
// The binary path is cargo's default-bin convention for the
// `computenet-iroh-sidecar` package, the same expression iroh/build.gradle.kts
// uses.
if (project.hasProperty("iroh.enabled")) {
    val sidecarBinary = File(rootDir, "iroh/sidecar/target/debug/computenet-iroh-sidecar")
    tasks.withType<Test>().configureEach {
        dependsOn(":iroh:cargoBuild")
        systemProperty("iroh.sidecar.binary", sidecarBinary.absolutePath)
        // computenet-o0m3.3: same forwarding as iroh/build.gradle.kts — pass
        // -Piroh.relay.url=<url> through as the JVM system property
        // SidecarProcess.spawn reads, so one -P flag steers every sidecar this
        // module's tests spawn (including IrohMirrorTransport's).
        if (project.hasProperty("iroh.relay.url")) {
            systemProperty("iroh.relay.url", project.property("iroh.relay.url") as String)
        }
    }
}

application {
    mainClass = "civictech.demo.beadsmirror.BeadsMirrorAppKt"
}
