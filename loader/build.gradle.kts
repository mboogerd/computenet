plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    //
    // Deliberately NOT `buildsrc.convention.ksp-cell`: `:loader` authors no
    // `@Contract`/`@CellBase` cells of its own. It *loads* modules that do — the cells
    // live in the fixture subprojects below, which each apply `ksp-cell` so their
    // `META-INF/services/civictech.nature.ContractModule` entries are real generator
    // output (epic computenet-051 risk 051-R7).
    id("buildsrc.convention.kotlin-jvm")
}

// JAR1 dependency shape, decided on feature computenet-051.1:
//
// - `api(:nature)` — the registries and descriptors a loaded module is registered
//   into are `:nature` types (`ContractRegistry`, `ContractDescriptor`,
//   `ContractModule`, `civictech.gen.wire.ProxyRegistry`), and they appear in
//   `:loader`'s own public surface, so they are `api` rather than `implementation`.
// - `api(:kernel)` — needed to spawn and host what a module carries, AND, since
//   feature computenet-051.4.2, `ModuleLoader.track(registry: LocationRegistry):
//   AutoCloseable` puts `civictech.cell.host.LocationRegistry` (a `:kernel` type)
//   on `:loader`'s own public signature. A caller cannot call `track` without
//   naming that type, so `implementation` would make `:loader` compile while
//   quietly breaking every consumer that doesn't also declare `:kernel` itself —
//   `api` says the truth.
// - `testImplementation(:testkit)` — test scaffolding only.
//
// `:kernel` and `:concord` MUST NOT depend on `:loader` (the loader sits *above* the
// runtime, never inside it, and concord stays implementation-neutral).
// `civictech.loader.ModuleDependencyTest` enforces that direction against those two
// build files' text, and enforces `:loader`'s own forbidden set against this file and
// against the module's runtime classpath — the same two-check shape
// `civictech.oracle.ModuleDependencyTest` uses, for the same reason: a text check sees
// a declaration no loaded class happens to name, and a classpath check sees a module
// arriving transitively that nobody declared here.
dependencies {
    api(project(":nature"))
    api(project(":kernel"))

    testImplementation(project(":testkit"))
}

// The fixture jars the loader's tests (and later features' tests) load.
//
// Each is a real Gradle subproject built through the ordinary pipeline, and its jar's
// absolute path is forwarded to the test JVM as a system property — the same
// "forward a path the tests could not otherwise know" idiom `:bench` uses for
// `civictech.bench.jmhBenchmarkList` and `:oracle` uses for `oracle.seeds`. Tests read
// them by these names:
//
//   loader.fixture.validBasic       — (a) one @Contract, one cell; the well-formed baseline
//   loader.fixture.noAttrs          — (b) (a)'s shape, but no manifest attributes. ERR-02
//   loader.fixture.emptyModule      — (c) manifest attributes, zero descriptors. DISC-05;
//                                      its version string is the DISC-04 verbatim-string fixture
//   loader.fixture.utilA            — (d) bundles com.example.Util whose tag() returns "A"
//   loader.fixture.utilB            — (d) bundles com.example.Util whose tag() returns "B"
//   loader.fixture.smuggler         — (e) bundles a class named civictech.cell.Cell
//   loader.fixture.throwingProvider — (f) a valid contract PLUS a hand-written WireSerializers
//                                      provider whose init throws. ERR-03 / atomicity probe
//   loader.fixture.missingSharedType — (g) a cell extending a type its compileOnly-only
//                                      dependency (:loader:fixtures:removed-api) supplies at
//                                      compile time but not on the built jar's classpath. ERR-04/B12
//   loader.fixture.doctoredNature   — (h) a real generated table, hand-doctored to swap one
//                                      PortDescriptor's natures for a non-default value. B2
//   loader.fixture.collidingContract — (i) reuses valid-basic's GreetingApi FQN (so its
//                                      generator-derived contractId collides) with a
//                                      different method shape. ERR-05's registration-
//                                      refusal arm (computenet-9fqe)
//
// `:loader:fixtures:removed-api` carries no property: it is a compileOnly-only helper for
// missing-shared-type, never itself a loadable module (no manifest attributes, no services
// entry, never on :loader's test runtime classpath).
val fixtureJarProperties = mapOf(
    "loader.fixture.validBasic" to ":loader:fixtures:valid-basic",
    "loader.fixture.noAttrs" to ":loader:fixtures:no-attrs",
    "loader.fixture.emptyModule" to ":loader:fixtures:empty-module",
    "loader.fixture.utilA" to ":loader:fixtures:util-a",
    "loader.fixture.utilB" to ":loader:fixtures:util-b",
    "loader.fixture.smuggler" to ":loader:fixtures:smuggler",
    "loader.fixture.throwingProvider" to ":loader:fixtures:throwing-provider",
    "loader.fixture.missingSharedType" to ":loader:fixtures:missing-shared-type",
    "loader.fixture.doctoredNature" to ":loader:fixtures:doctored-nature",
    "loader.fixture.collidingContract" to ":loader:fixtures:colliding-contract",
)

// Cross-project task access needs the other project evaluated first. These are this
// project's own children, so the dependency is acyclic by construction; the repository
// already reaches across projects this way (kernel/build.gradle.kts wires `:gen:test`
// ahead of `:kernel:compileKotlin`).
fixtureJarProperties.values.forEach { evaluationDependsOn(it) }

tasks.withType<Test>().configureEach {
    fixtureJarProperties.forEach { (property, projectPath) ->
        val jarTask = project(projectPath).tasks.named<Jar>("jar")
        dependsOn(jarTask)
        systemProperty(property, jarTask.get().archiveFile.get().asFile.absolutePath)
    }
}
