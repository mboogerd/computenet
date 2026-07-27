package buildsrc.convention

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.getByType

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// Shared test stack: every module that runs JUnit5 tests needs these. Modules that
// also depend on :testkit get JUnit/kotlin-test transitively (testkit exposes them
// as `api`), but declaring them here too is harmless and keeps modules that don't
// use :testkit (concord, wire, demo:shell, ...) self-sufficient.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(libs.findLibrary("kotest-assertions-core").get())
    "testImplementation"(libs.findLibrary("junit").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform").get())
    "testImplementation"(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The whole kernel suite runs in one JVM fork; ProtocolSupport keys ports in a
    // JVM-global map whose handler-closure values reference their keys, so ports
    // created across the suite accumulate (a pre-existing structural retention the
    // per-node policy work nudged past a constrained default heap). 2g gives margin;
    // forkEvery bounds accumulation by periodically starting a fresh test JVM.
    maxHeapSize = "2g"
    setForkEvery(80)
    // Hang -> failure, not a silently stuck build. 440+ unbudgeted runToIdle() call
    // sites and zero prior @Timeout meant a livelock regression could hang CI
    // indefinitely. 5 minutes is deliberately generous (seed sweeps); raise per-class
    // with @Timeout if a legitimate test needs more, don't raise this default.
    systemProperty("junit.jupiter.execution.timeout.testable.method.default", "5m")
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED
        )
    }
}
