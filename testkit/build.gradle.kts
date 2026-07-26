plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
}

// :testkit is consumed as `testImplementation` by :kernel and every demo module —
// its helpers live in src/main so a plain project dependency is enough to reach
// them from a consumer's test source set. JUnit is exposed as `api` so consumers
// get it transitively (HttpProbe/awaitUntil throw org.opentest4j.AssertionFailedError
// on timeout, matching the existing local copies).
dependencies {
    api(project(":kernel"))

    api(libs.junit)
    api(kotlin("test"))
    runtimeOnly(libs.junit.platform)
}
