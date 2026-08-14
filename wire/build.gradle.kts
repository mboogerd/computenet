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
//
// computenet-piur: NOTHING in the repo used to fail if this list was emptied.
// Both prior guards miss it by construction — the end-to-end test in
// `WsAnnouncementStressTest` sets the properties with `System.setProperty`
// inside the already-running test JVM, so it never crosses this seam, and the
// `announcement-probe.yml` poison preflight (which does cross it, properly) is
// workflow_dispatch-only and not a required check. Measured on this file's
// parent commit: deleting this whole block and running
// `./gradlew :wire:test --tests '*WsAnnouncementStressTest*' --rerun
// -Dwire.stress.injectFailureAt=1` left all three tests PASSED.
//
// So the list is now PUBLISHED into the test JVM as `wire.forwardedKeys`, and
// `WireSystemPropertyForwardingTest` — an ordinary `:wire` test, on the
// required `build-test-fast` lane — requires that published set to cover every
// `wire.*` system property the `:wire` test sources actually read. Emptying,
// shortening, or deleting this block therefore reddens a check that runs on
// every PR. Adding a NEW `-Dwire.*` knob to a test without adding it here does
// too, which is the regression that has now happened twice (dqy.45, dqy.63).
//
// Note the list gained `wire.stress.artifacts` and `wire.stress.heapCeiling`
// when that test was written: both are read from `:wire` test sources
// (`artifactSink()` is on the `@Test` path) and neither was forwarded, i.e.
// `./gradlew :wire:test -Dwire.stress.artifacts=DIR` was silently ignored —
// the same defect, found by the guard on its first run.
val forwardedSystemProperties = listOf(
    "wire.burst.iterations",
    "wire.burst.refs",
    "wire.stress.iterations",
    "wire.stress.injectFailureAt",
    "wire.stress.artifacts",
    "wire.stress.heapCeiling",
)

tasks.withType<Test>().configureEach {
    forwardedSystemProperties.forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    // The guard's only channel. It is deliberately the SAME list object the
    // loop above forwards, so the two cannot drift: there is no second literal
    // to keep in sync.
    systemProperty("wire.forwardedKeys", forwardedSystemProperties.joinToString(","))
}
