plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
}

// ponytail: the WebSocket dependency lives here so :kernel stays dependency-free;
// another transport = another small module behind the same bridge cells.
dependencies {
    implementation(project(":kernel"))
    implementation(libs.java.websocket)
}

// The burst/stress probes read `wire.burst.*` and `wire.stress.*` from the JVM
// they run in. Gradle's Test task does NOT inherit the daemon's system
// properties, so without this forwarding `./gradlew ... -Dwire.burst.iterations=N`
// is silently ignored and the probe runs at its committed fast-lane size —
// which is exactly the trap when someone tries to turn the fast lane into the
// long measurement the KDoc points them at (computenet-dqy.45 review, measured:
// `-Dwire.burst.iterations=2` still ran 10 iterations before this).
//
// `wire.stress.injectFailureAt` (computenet-dqy.63) is the same trap's other
// half: WsAnnouncementStressTest's `@Test` reads it, but a value that never
// reaches the test JVM is indistinguishable from one nobody passed, so this
// key has to be forwarded exactly like the others or `-Dwire.stress.
// injectFailureAt=N` silently does nothing under `./gradlew :wire:test`.
tasks.withType<Test>().configureEach {
    listOf(
        "wire.burst.iterations",
        "wire.burst.refs",
        "wire.stress.iterations",
        "wire.stress.injectFailureAt",
    ).forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
