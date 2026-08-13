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
tasks.withType<Test>().configureEach {
    listOf("wire.burst.iterations", "wire.burst.refs", "wire.stress.iterations").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
