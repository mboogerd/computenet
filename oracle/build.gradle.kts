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
