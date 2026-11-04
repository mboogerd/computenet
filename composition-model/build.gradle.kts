plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
}

// :composition-model is an executable model of the DESIGN in
// doc/integration/2026-10-04-composition/ (per-link-positions.md and
// composite-obligation-holders.md), not of the kernel. It deliberately depends on NO other
// project: it must stay independent of the implementation so that it can later serve as an
// oracle for it, the same independence argument :oracle makes for its reference ops.
// `civictech.compmodel.ModuleDependencyTest` enforces this against this file's text AND
// against the test runtime classpath (a `civictech.cell.Cell` fingerprint).
dependencies {
}

// `-Pcompmodel.seeds=N` widens (or narrows) the seeded random-walk sweeps with no source
// change. Forwarded only when present, so the default count stays in Kotlin
// (`civictech.compmodel.check.Seeds.DEFAULT_COUNT`). N is a COUNT: walks use seeds
// `0 until N`, so a widened run is a superset of the default one and a failing seed keeps
// its identity.
tasks.withType<Test>().configureEach {
    (project.findProperty("compmodel.seeds") as String?)?.let { systemProperty("compmodel.seeds", it) }
    // The experiments write their measured numbers (state counts, depths, verdicts) here;
    // model-results.md quotes them.
    val report = layout.buildDirectory.file("compmodel/report.txt").get().asFile
    systemProperty("compmodel.report", report.absolutePath)
    // One report per run: experiments append, so the file is cleared before the task runs.
    doFirst { report.delete() }
}
