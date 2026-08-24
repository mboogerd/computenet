plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // The DST rig's replay artifact (civictech.testkit.dst.DstArtifact) is JSON on disk —
    // [CHA1-31]. The plugin is here rather than a hand-rolled encoder because the artifact is
    // the rig's evidence of its own claims: a mis-escaped fault parameter would replay a
    // *different* plan and report REPLAYED, which is exactly the failure mode the artifact
    // exists to rule out. Same catalog alias :kernel and :wire already apply.
    alias(libs.plugins.kotlin.plugin.serialization)
}

// :testkit is consumed as `testImplementation` by :kernel and every demo module —
// its helpers live in src/main so a plain project dependency is enough to reach
// them from a consumer's test source set. JUnit is exposed as `api` so consumers
// get it transitively (HttpProbe/awaitUntil throw org.opentest4j.AssertionFailedError
// on timeout, matching the existing local copies).
dependencies {
    api(project(":kernel"))

    // `api`, not `implementation`: FaultCodec/FaultRecord expose kotlinx.serialization.json.JsonObject
    // as the open-ended per-fault parameter slot, so a consumer that registers a codec needs the
    // type on its own compile classpath. Declaring it `implementation` while exposing it in public
    // signatures would compile here and fail at every consumer's use site. :testkit is consumed as
    // `testImplementation` by :kernel and every demo, so this reaches their TEST classpaths only —
    // no main classpath gains a dependency, and the version is the catalog's (:kernel and :wire
    // already depend on the same artifact).
    api(libs.kotlinx.serialization)

    api(libs.junit)
    api(kotlin("test"))
    runtimeOnly(libs.junit.platform)
}
