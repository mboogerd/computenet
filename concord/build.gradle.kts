plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
}

dependencies {
    // The kernel binding (civictech.concord.driver.kernel) lands in W1-A; the
    // neutral SPI/check/schema packages must not import civictech.cell.* even
    // though the module depends on :kernel.
    implementation(project(":kernel"))
    // @Serializable data classes in main (schema + value model).
    implementation(libs.kotlinx.serialization)

    // kaml is the YAML front end. All scenario parsing happens in the test
    // source set (the runner is a JUnit harness — W1-A), so kaml is test-scope.
    // 0.77.1 is built against kotlinx-serialization 1.9.0 (the version this repo
    // pins) and resolves cleanly under Kotlin 2.1.21.
    testImplementation("com.charleskorn.kaml:kaml:0.77.1")

    // Same test stack the kernel uses.
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.platform)
    testImplementation(kotlin("test"))
}

// W2 profile filter: the corpus runner reads `concord.profiles` (default `core`)
// to select which scenarios execute by their `profile:` field (P9). Threaded
// from the Gradle project property `-Pconcord.profiles=core,dist,dur` into the
// test JVM as a system property. All four W2 pilots are `core`, so the default
// leaves behaviour unchanged; `-Pconcord.profiles=dist` runs zero pilots.
tasks.withType<Test>().configureEach {
    systemProperty("concord.profiles", (project.findProperty("concord.profiles") as String?) ?: "core")
    // W4-C: the generative sweep (24-GEN-01) defaults to its `generator: instances:`
    // count; `-Pconcord.gen.instances=N` overrides it for a deeper local sweep.
    (project.findProperty("concord.gen.instances") as String?)?.let { systemProperty("concord.gen.instances", it) }
}

// W1-D: the concordance generator (Concord §1.5 / `concord/schema/provenance.md`).
// A JavaExec (not a custom Gradle task type) running civictech.concord.provenance's
// main(), so the lint/scan logic stays plain Kotlin in `main` — unit-testable on its
// own and free of the Gradle API. Report-only by default; pass
// `-Pconcord.fatal=true` to fail the build when dangling/orphan lints fire.
// Writes the human-facing concordance to doc/spec/CONCORDANCE.md.
tasks.register<JavaExec>("concordance") {
    group = "verification"
    description = "Generates doc/spec/CONCORDANCE.md from L0 requirement ids and corpus " +
        "covers: tags; lints dangling covers / orphan scenarios (fatal) and coverage gaps " +
        "(report-only). Pass -Pconcord.fatal=true to fail on fatal findings."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("civictech.concord.provenance.ConcordanceKt")
    val fatal = (project.findProperty("concord.fatal") as String?).toBoolean()
    args(
        rootProject.layout.projectDirectory.dir("doc/spec").asFile.path,
        layout.projectDirectory.dir("corpus").asFile.path,
        rootProject.layout.projectDirectory.file("doc/spec/CONCORDANCE.md").asFile.path,
        fatal.toString(),
    )
}

// W2: wire the concordance lint into the build gate. `concordanceGate` runs the
// same scanner in FATAL mode — a dangling `covers:` id or an orphan (empty
// covers) scenario fails the build; coverage-gap notes stay non-fatal. Output
// goes to the throwaway build dir (not the tracked doc/spec copy) so the gate
// never dirties the working tree. `check` depends on it, so `:concord:check`
// (and any `:concord:build`) enforces a clean corpus lineage now that the pilots'
// covers are reconciled to real EARS ids.
val concordanceGate = tasks.register<JavaExec>("concordanceGate") {
    group = "verification"
    description = "Fails the build on dangling/orphan covers lints (concordance in FATAL mode); " +
        "coverage-gap notes remain non-fatal. Wired into `check`."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("civictech.concord.provenance.ConcordanceKt")
    args(
        rootProject.layout.projectDirectory.dir("doc/spec").asFile.path,
        layout.projectDirectory.dir("corpus").asFile.path,
        layout.buildDirectory.file("concordance/CONCORDANCE.md").get().asFile.path,
        "true",
    )
}

// T02-C: three small doc-integrity lints beside the concordance gate (same
// idiom — plain Gradle/Kotlin JavaExec, fatal listing on failure).
// Package-pointer resolution and Status-header vocabulary are fatal;
// requirement-id density is report-only (visibility, not a forcing
// function). Wired into `check` so a lying header (e.g. a `cell.attention.*`
// reference to a package that does not exist) fails the build at the source
// instead of drifting until an agent trips over it.
val docLints = tasks.register<JavaExec>("docLints") {
    group = "verification"
    description = "Fails the build on unresolved cell.<pkg>.<Type> doc pointers or Status-header " +
        "vocabulary violations; reports zero-id chapter density. Wired into `check`."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("civictech.concord.lint.DocLintsKt")
    args(
        rootProject.layout.projectDirectory.dir("doc/spec").asFile.path,
        rootProject.layout.projectDirectory.dir("kernel/src/main/kotlin/civictech/cell").asFile.path,
        "true",
    )
}

tasks.named("check") {
    dependsOn(concordanceGate, docLints)
}
