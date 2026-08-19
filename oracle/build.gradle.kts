plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    //
    // Deliberately NOT `buildsrc.convention.ksp-cell`: the oracle authors no
    // `@Contract`/`@CellBase` cells, it composes the ones :kernel already ships
    // (epic computenet-4ru §2.1). If a generated case ever needs a bespoke collector
    // cell, register its ports directly, as GenerativeGraphTest.CounterCollectorCell does.
    id("buildsrc.convention.kotlin-jvm")
}

// Sources live in src/main (the :testkit template) so consumers reach them from their
// own TEST source sets through a plain `testImplementation(project(":oracle"))` with no
// further configuration — that plain-dependency consumability is [ORA1-API-01] itself,
// and `kernel/src/test/kotlin/civictech/cell/oracle/OracleConsumerTest.kt` is its proof.
//
// [ORA1-API-04]: no dependency on :concord, :wire, :inspect, or any :demo:* module.
// :concord is the sharpest of those — AGENTS.md reserves `civictech.cell.*` imports to
// `civictech.concord.driver.kernel`, and the oracle is kernel-coupled by construction
// (epic §9 risk 6, decided D1 on computenet-4ru.3: separate module, concord stays
// implementation-neutral). `civictech.oracle.ModuleDependencyTest` enforces the rule
// against this file's text AND against the module's own runtime classpath, so adding a
// forbidden line here reddens :oracle:test rather than passing unnoticed.
dependencies {
    api(project(":kernel"))
    api(project(":testkit"))
}

// [ORA1-PERF-02]: `-Poracle.seeds=N` widens (or narrows) a seed sweep with no source change.
// Forwarded to the test JVM as a system property, following the idiom the concord build file
// uses, and forwarded ONLY when present so the default count stays in Kotlin —
// `civictech.oracle.run.OracleSweep.DEFAULT_SEED_COUNT` is where it lives, next to the
// measurement that sized it, and a default duplicated here would silently outrank that KDoc.
//
// N is a seed COUNT, not a range: OracleSweep.defaultSeeds() sweeps `0 until N`, so a widened
// run is a superset of the default one and a failing seed keeps its identity.
tasks.withType<Test>().configureEach {
    (project.findProperty("oracle.seeds") as String?)?.let { systemProperty("oracle.seeds", it) }
}
