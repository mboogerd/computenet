plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts` and
    // `buildSrc/src/main/kotlin/ksp-cell.gradle.kts`.
    id("buildsrc.convention.ksp-cell")
    alias(libs.plugins.kotlin.plugin.serialization)
}
dependencies {
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization)
    api(project(":nature"))
    // civictech.gen.wire.{Contract,Key,Protocol,CellBase,ProxyRegistry} — the
    // annotations cell/port authors apply and the generated-proxy lookup — live in
    // :nature (T09 §A), reachable via the api dependency above. ksp-cell's
    // `ksp(project(":gen"))` is processor-time only: :gen (KotlinPoet, KSP's
    // symbol-processing-api, kotlin-reflect) never lands on kernel's classpath.

    testImplementation(project(":testkit"))

    // [ORA1-API-01]: the :oracle differential-test module must be consumable from another
    // module's test source set through a plain project dependency and nothing else. This
    // line plus civictech.cell.oracle.OracleConsumerTest is that requirement's proof — no
    // repositories block, no extra configuration, no source-set wiring. Test-scope only;
    // :oracle never reaches kernel's main classpath.
    testImplementation(project(":oracle"))

    // [DSC1-WIRE-04] is a constraint on the MAIN classpath: `:kernel` main depends on
    // neither `:identity` nor any cryptographic provider, and no `civictech.identity`
    // import appears in kernel main. This is test scope only, on exactly the `:oracle`
    // precedent above (`:identity` itself depends on `:kernel`), and it exists so
    // `SignedAnnouncementTest` can drive the ingress admission gate with REAL Ed25519
    // keypairs and the real canonical announcement encoder rather than a stand-in —
    // the gate's whole subject is whether a signature verifies, and a fake verifier
    // would be testing the fake.
    testImplementation(project(":identity"))

    // CHA2's @ExpectedFailure self-test drives fixture classes through a nested JUnit
    // Platform execution and asserts over the resulting events — the only way to prove a
    // verdict-inverting extension without the proof itself reddening the build. The BOM
    // keeps the platform version derived from the one Jupiter version the catalog pins.
    // Test-scope only; nothing here reaches kernel's main classpath.
    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation("org.junit.platform:junit-platform-testkit")
}

// CHA2's per-run expected-failure ledger — [CHA2-45], BS-16: the reproductions still failing
// for their recorded reason are listed, with reason and owner, in the build's own output, so
// the residual ledger is an artifact rather than something a reader reconstructs from test
// sources.
//
// Why a file and not an end-of-JVM hook: `:kernel:test` runs its suite across several test
// JVMs (`maxParallelForks`, `forkEvery(80)` in buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts),
// so whatever a single JVM prints at its own shutdown is a fraction of the run reported as
// the total. Every fork appends (under an exclusive file lock) to the one file below; this
// task truncates it before the run and renders it after, which is the only vantage point
// that sees the whole run.
//
// The path is deliberately not passed as a system property: system properties are inputs to
// the Test task, and an absolute machine-specific one would cost the task its cross-machine
// build-cache hits. A Gradle Test task's working directory is the project directory, so
// ExpectedFailureLedger.DEFAULT_REPORT_PATH — a relative path — resolves to exactly this
// file. Keep the two in step; printing the path below is what makes a mismatch visible.
//
// Printing the path is NOT by itself what stops a silent zero, and the absent-file branch
// below is. Measured 2026-08-16 during this feature's review: with the report file deleted
// and `:kernel:test` UP-TO-DATE (the same shape a build-cache hit produces on a fresh
// machine — `build/reports/**` is not a declared output of the test task, so a FROM-CACHE
// `:kernel:test` does not restore it), this task printed `Standing expected failures
// (@ExpectedFailure): 0` next to a correct path, which reads as "nothing stands" when the
// truth is "nothing ran". An empty-but-present file is the real zero — `doFirst` truncates
// it whenever the test task actually executes — so absent and empty are reported apart.
// The renderer is a FINALIZER rather than a `doLast` on `test`, because a `doLast` is
// skipped when the task fails — and a run with a failing test is exactly when a reader wants
// to know which of the failures were the standing, expected ones.
val expectedFailureReport = layout.buildDirectory.file("reports/expected-failures/standing.tsv")

val reportExpectedFailures = tasks.register("reportExpectedFailures") {
    description = "Prints the expected failures still standing after :kernel:test [CHA2-45]."
    val reportFile = expectedFailureReport.get().asFile
    doLast {
        if (!reportFile.isFile) {
            logger.lifecycle(
                "Standing expected failures (@ExpectedFailure): NOT REPORTED — :kernel:test " +
                    "did not execute in this build (up-to-date, or restored from the build " +
                    "cache, which does not restore this file), so no per-run ledger was " +
                    "written. This is not a count of zero. Re-run with --rerun for the " +
                    "current list. (${reportFile.absolutePath})"
            )
            return@doLast
        }
        val entries = reportFile.readLines().filter { it.isNotBlank() }.distinct()
        logger.lifecycle(
            buildString {
                appendLine("Standing expected failures (@ExpectedFailure): ${entries.size}")
                entries.forEach { line ->
                    val fields = line.split("\t") + List(5) { "" }
                    appendLine("  - ${fields[0]}  [owner ${fields[2]}, signature ${fields[1]}]")
                    appendLine("      reason:  ${fields[3]}")
                    appendLine("      filedAs: ${fields[4]}")
                }
                append("  (${reportFile.absolutePath})")
            }
        )
    }
}

tasks.named<Test>("test") {
    val reportFile = expectedFailureReport.get().asFile
    finalizedBy(reportExpectedFailures)
    doFirst {
        reportFile.parentFile.mkdirs()
        reportFile.writeText("")
    }
}

// :gen's own test suite (ContractProcessorTest, NatureDescriptorSweepTest) is the
// real generator-regression gate; wiring it ahead of compileKotlin makes
// doc/ARCHITECTURE.md's "generator regressions fail before kernel compiles" claim
// true (the deleted :gen-test module was a verified no-op: zero sources, NO-SOURCE
// on every task).
tasks.named("compileKotlin") {
    dependsOn(project(":gen").tasks.named("test"))
}