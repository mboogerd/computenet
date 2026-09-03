plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    //
    // Deliberately NOT `buildsrc.convention.ksp-cell`: `:query` authors no
    // `@Contract`/`@CellBase` cell — it compiles a Datalog/relational query down to a
    // kernel `civictech.cell.graph.GraphSpec` (epic computenet-cab §2.1, §4.7), it does not
    // author cells of its own ([QRY1-LOWER-03]). AST and diagnostic types are plain
    // `java.io.Serializable` data (the `GraphDsl.kt:232` precedent, not
    // kotlinx-serialization), so no serialization compiler plugin is applied either.
    id("buildsrc.convention.kotlin-jvm")
}

// Sources live in src/main (the :testkit/:oracle template) so a consumer reaches :query
// from its own main source set through a plain `implementation(project(":query"))` with no
// further configuration ([QRY1-API-01]).
//
// [QRY1-API-02]: no dependency on :concord, :wire, :inspect, or any :demo:* module.
// `civictech.query.ModuleDependencyTest` enforces the rule against this file's text and the
// module's own runtime classpath, following `civictech.oracle.ModuleDependencyTest`'s
// precedent.
//
// `:oracle` is test-scope only — it is the differential-testing harness this module's own
// tests are built against (mirroring how :kernel consumes :oracle), not a runtime
// dependency of the compiler itself.
dependencies {
    api(project(":kernel"))
    testImplementation(project(":testkit"))
    testImplementation(project(":oracle"))
}
