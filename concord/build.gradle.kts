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

// W1-D: the concordance generator (Concord §1.5 / `concord/schema/provenance.md`).
// A JavaExec (not a custom Gradle task type) running civictech.concord.provenance's
// main(), so the lint/scan logic stays plain Kotlin in `main` — unit-testable on its
// own and free of the Gradle API. Report-only by default; pass
// `-Pconcord.fatal=true` to fail the build when dangling/orphan lints fire.
// Deliberately NOT wired into `check` here — the corpus is mid-construction and the
// pilot `covers:` ids are provisional (see provenance.md's note), so fatal-on-`check`
// is W2's job once the pilots' covers are reconciled against real EARS ids.
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
