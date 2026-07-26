package buildsrc.convention

import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The whole kernel suite runs in one JVM fork; ProtocolSupport keys ports in a
    // JVM-global map whose handler-closure values reference their keys, so ports
    // created across the suite accumulate (a pre-existing structural retention the
    // per-node policy work nudged past a constrained default heap). 1g gives margin;
    // forkEvery bounds accumulation by periodically starting a fresh test JVM.
    maxHeapSize = "1g"
    setForkEvery(80)
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED
        )
    }
}
