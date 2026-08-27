plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    // Toolchain 21, the JUnit5 stack, 2g test forks, forkEvery(80) and the 5m
    // per-method timeout all come from there — do not re-declare any of them here.
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.jmh)
}

// :bench is the repository's benchmark module [BEN1-01]. Three source sets:
//
//   src/main/kotlin  package civictech.bench        shared fixtures/generators [BEN1-08]
//   src/jmh/kotlin   package civictech.bench.micro  @Benchmark bodies          [BEN1-06]
//   src/test/kotlin  package civictech.bench        fast unit tests (sub-second)
//
// `jmh` and `test` both compile against `main`'s output, which is why fixtures live in
// `main` and not in either leaf. Project dependencies are :kernel and :testkit and
// nothing else [BEN1-03] — benchmarks drive existing kernel cells, so the ksp-cell
// convention is deliberately NOT applied: this module authors no cells of its own.
dependencies {
    implementation(project(":kernel"))
    implementation(project(":testkit"))
}

jmh {
    // Drive the harness version from the catalog pin (`jmh = "1.37"`), so jmh-core and
    // the generator artifacts the plugin resolves cannot drift from it [BEN1-05].
    jmhVersion = libs.versions.jmh
}

// -----------------------------------------------------------------------------------
// HOW KOTLIN @Benchmark METHODS ACTUALLY GET DISCOVERED HERE — measured, not assumed.
//
// The usual Kotlin+JMH trap is that JMH's canonical generator is an ANNOTATION
// PROCESSOR (jmh-generator-annprocess), javac only runs annotation processors over
// JAVA sources, and so a project whose benchmarks are Kotlin produces zero benchmarks
// while the build stays green. That trap does not apply to this wiring, and the reason
// is worth writing down because it decides whether kapt is needed (it is not):
//
//   me.champeau.jmh 0.7.3 does not use the annotation processor at all. It resolves
//   `org.openjdk.jmh:jmh-generator-bytecode` into the `jmh` configuration and runs it
//   as the `jmhRunBytecodeGenerator` task, over COMPILED CLASSES. Kotlin bytecode
//   carries the same @Benchmark annotations as Java bytecode, so the generator sees
//   them regardless of source language.
//
// Verified 2026-08-17 on this branch by running `:bench:jmhRunBytecodeGenerator` and
// reading what it wrote, rather than by trusting a zero exit code:
//   - bench/build/jmh-generated-sources/civictech/bench/micro/jmh_generated/ holds
//     SmokeBenchmark_jmhType{,_B1,_B2,_B3}.java and SmokeBenchmark_baseline_jmhTest.java
//     — the `_jmhType` classes generated from a KOTLIN source.
//   - bench/build/jmh-generated-resources/META-INF/BenchmarkList holds one record,
//     `civictech.bench.micro.SmokeBenchmark ... baseline ... AverageTime`.
//   - `:bench:dependencies` shows jmh-core:1.37 and jmh-generator-bytecode:1.37 on the
//     `jmh` configuration, and `jmhAnnotationProcessor` EMPTY.
//
// jmh-generator-annprocess is pinned in gradle/libs.versions.toml [BEN1-05] but is
// deliberately NOT wired into any configuration. That is a decision, not an oversight:
// it is the generator for the Java-source route, and adding it alongside the bytecode
// generator would make any future bench/src/jmh/java source generate its `_jmhType`
// classes twice — once by javac, once by jmhRunBytecodeGenerator — which fails as a
// duplicate-class error. The pin exists so that a future switch to that strategy is a
// one-line wiring change against an already-pinned version.
// -----------------------------------------------------------------------------------

// The zero-benchmark guard [BEN1-07].
//
// Everything above describes the pipeline working. The point of this task is the case
// where it silently stops: a green `:bench:build` that generated no benchmarks at all
// is indistinguishable from a healthy one by exit code, and it is the single most
// likely way this module rots. So the emptiness of the generated benchmark list is a
// BUILD FAILURE, wired into `check` (and therefore into `build`), naming the source
// set a reader has to go look at.
//
// The hook is a separate verification task reading the generated META-INF/BenchmarkList
// rather than a `doLast` on the generator. Note WHY, because the obvious reason is the
// wrong one: it is not that the generator gets skipped on an empty source set. Measured
// 2026-08-17 with bench/src/jmh/kotlin moved aside, `:bench:jmhRunBytecodeGenerator`
// still EXECUTED and simply truncated BenchmarkList to zero bytes — the silently-empty
// success this guard exists to catch. The reasons are that neither the generator nor
// `jmhJar` is in the `build` lifecycle (so something has to pull the generator into
// `check` regardless), and that a named `verifyBenchmarkDiscovery FAILED` line says what
// went wrong before anyone reads the message.
//
// Deliberately declares no outputs, so it is never up-to-date and never restored from
// the build cache. This is a cheap file read, and a guard that can be skipped is not a
// guard.
val benchmarkList = layout.buildDirectory.file("jmh-generated-resources/META-INF/BenchmarkList")

val verifyBenchmarkDiscovery = tasks.register("verifyBenchmarkDiscovery") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails the build if the JMH generator discovered zero benchmarks [BEN1-07]."
    // Compiling the generated Java is part of what is being verified: generation that
    // emits uncompilable code is as broken as generation that emits nothing.
    dependsOn(tasks.named("jmhCompileGeneratedClasses"))

    val listFile = benchmarkList
    doLast {
        val file = listFile.get().asFile
        // BenchmarkList is a line-per-benchmark text file; `#` introduces a comment.
        val records = if (file.isFile) {
            file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        } else {
            emptyList()
        }
        if (records.isEmpty()) {
            throw GradleException(
                "JMH discovered ZERO benchmarks. The benchmark source set " +
                    "bench/src/jmh/kotlin produced no @Benchmark methods, so " +
                    "${file.absolutePath} is " +
                    (if (file.isFile) "empty" else "absent") + ". " +
                    "This is a build failure on purpose [BEN1-07]: a benchmark module " +
                    "that generates nothing would otherwise report a successful run. " +
                    "Either restore an @Benchmark method under bench/src/jmh/kotlin " +
                    "(civictech.bench.micro.SmokeBenchmark is the permanent discovery " +
                    "sentinel and is not safe to delete), or check that " +
                    ":bench:jmhRunBytecodeGenerator still sees Kotlin bytecode."
            )
        }
        logger.lifecycle(
            "JMH discovery: ${records.size} benchmark(s) generated from " +
                "bench/src/jmh/kotlin [BEN1-07]."
        )
    }
}

tasks.named("check") {
    dependsOn(verifyBenchmarkDiscovery)
}

// Runtime-readable companion to the guard above: BenchmarkDiscoverySmokeTest and
// ProjectGraphTest, both in bench/src/test/kotlin/civictech/bench [BEN1-04, BS-2's
// companion-test clause, BS-6]. Two things only this build script can hand a JUnit
// test running inside :bench:test:
//
//   - the generated BenchmarkList's absolute path, because the test source set has
//     no other way to find `build/jmh-generated-resources/...` reliably — reusing
//     the same `benchmarkList` provider the guard task above reads keeps both
//     readers pointed at one location instead of two paths that could drift apart;
//   - :bench:test depending on `jmhRunBytecodeGenerator` (the task that WRITES
//     BenchmarkList — see the "HOW KOTLIN @Benchmark METHODS ACTUALLY GET
//     DISCOVERED HERE" comment above for why generation is bytecode-based, not
//     annotation-processing), so the file exists — even if empty — by the time the
//     test runs. The lighter generator task is enough here, not
//     `jmhCompileGeneratedClasses`: the smoke test only reads the generated
//     resource, it never touches the compiled `_jmhType` classes.
//
// Neither test executes a benchmark: `:bench:jmh` and `:bench:jmhJar` stay
// unreachable from `:bench:test`/`:bench:build`/`check`, exactly as before this
// wiring — a JMH fork is minutes, and these tests are sub-second by design.
tasks.named<Test>("test") {
    dependsOn(tasks.named("jmhRunBytecodeGenerator"))

    // Bench-only console echo (computenet-rd7h, follow-up from computenet-x9e.15's
    // review). A `@Tag("bench")` sampling/rendering test's whole deliverable is a
    // printed tally (see CellFootprintProbeTest, ThroughputReportTest's render entry
    // points): by default Gradle's `test` task never forwards a test JVM's stdout to
    // the console, so `-PbenchOnly=true --tests '<Name>'` prints only `<Name> PASSED`
    // and the tally is reachable only from `<system-out>` inside the JUnit XML at
    // `bench/build/test-results/test/TEST-<fully.qualified.Name>.xml`
    // (doc/bench/regression-series.md §10).
    //
    // `testLogging.showStandardStreams` is configured for EVERY module via
    // `tasks.withType<Test>().configureEach` in
    // `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts` — turning it on THERE would echo
    // every module's test stdout on every `./gradlew test`, which is why §10 left this
    // as a documented XML workaround rather than a code fix. This is the narrower
    // alternative that §10 did not evaluate: a `:bench`-LOCAL override, gated on the
    // same `-PbenchOnly` property that already selects the bench tag above, so it only
    // ever changes behavior for a deliberately-invoked bench run.
    //
    // Demonstrated 2026-08-27:
    //   - `./gradlew :bench:test -PbenchOnly=true --tests
    //     'civictech.bench.micro.CellFootprintProbeTest' --rerun` echoes the probe's
    //     printed tally directly to the CONSOLE (not just the JUnit XML).
    //   - a default `./gradlew :bench:test --rerun` (no `-PbenchOnly`) shows no test
    //     stdout at all — this override does not touch the ordinary gate.
    if (project.hasProperty("benchOnly")) {
        testLogging {
            showStandardStreams = true
        }
    }
    // ProjectGraphTest [BEN1-04, BS-6] parses settings.gradle.kts and every module's
    // build.gradle.kts from the checkout root. A system property set here, at
    // configuration time, is a configuration-cache-safe constant — the same
    // reasoning the guard task's comment above gives for capturing a Provider
    // rather than reaching for `project` at execution time.
    systemProperty("computenet.repo.root", rootDir.absolutePath)
    systemProperty("civictech.bench.jmhBenchmarkList", benchmarkList.get().asFile.absolutePath)

    // Forwarded to the test JVM for ThroughputReportRenderTest — the @Tag("bench") entry
    // point that renders a JMH results file through ThroughputReport, and therefore
    // through F3's Findings writer. A `-D` on the Gradle command line sets the property on
    // the DAEMON, not on the forked test JVM, so without these lines the rendering entry
    // point has no way to be told which file to read.
    //
    // Read through `providers` and forwarded only when actually set, so the ordinary
    // `./gradlew :bench:test` — where none of them is set — keeps a stable task input and
    // stays cacheable. Reading at configuration time is configuration-cache-safe, and the
    // value becomes a declared input of the cache entry, which is what stops a different
    // results file from replaying a previous render.
    listOf(
        "civictech.bench.jmhResults",
        "civictech.bench.harnessSha",
        "civictech.bench.date",
        "civictech.bench.subject",
    ).forEach { name ->
        val forwarded = providers.systemProperty(name).getOrElse("")
        if (forwarded.isNotBlank()) systemProperty(name, forwarded)
    }

    // BOTH guard tests read files through those system properties — a path string is
    // not a task input, so without the two declarations below Gradle has no idea the
    // test's result depends on the file's CONTENT. Measured on this branch before the
    // declarations existed: with `implementation(project(":bench"))` added to
    // wire/build.gradle.kts, `./gradlew :bench:test` reported `:bench:test UP-TO-DATE`
    // and `BUILD SUCCESSFUL`; with bench/src/jmh/kotlin moved aside, the generator came
    // back FROM-CACHE with a zero-byte BenchmarkList and the test task was again
    // UP-TO-DATE and green. Only `--rerun` made either violation visible, so
    // [BEN1-04]'s "a fast unit test in :bench:test SHALL fail" did not hold on an
    // incremental build — and `org.gradle.caching=true` plus setup-gradle's cache
    // restore put the same replay inside the required checks. This repository has
    // already been bitten by exactly this shape (see .github/workflows/ci.yml's
    // `--rerun` note on :demo:beadsmirror:test: "whether `bd` is on PATH is NOT a
    // declared input of the test task, so a build-cache entry recorded before the
    // install step existed replays its SKIPPED report verbatim").
    //
    // Declaring the real inputs — rather than `upToDateWhen { false }` — keeps the
    // task cacheable in the overwhelmingly common case where nothing it reads moved,
    // while making a violation change the cache key so the assertion actually runs.
    inputs.file(benchmarkList)
        .withPropertyName("jmhBenchmarkList")
        // Content is what the assertion reads; the absolute path varies per checkout
        // and must not be part of the key, or no cache entry would ever hit.
        .withPathSensitivity(PathSensitivity.NONE)
    inputs.files(
        // Exactly the files ProjectGraphTest parses: the settings script, plus every
        // module's build script at the one and two segment depths settings.gradle.kts
        // actually uses (`:kernel`, `:demo:shopping`). Kept as a pattern rather than an
        // enumeration so that a module added without touching this file is still
        // covered.
        fileTree(rootDir) {
            include("settings.gradle.kts")
            include("*/build.gradle.kts")
            include("*/*/build.gradle.kts")
            exclude("**/build/**")
            exclude("**/node_modules/**")
        },
    )
        .withPropertyName("moduleBuildScripts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// -----------------------------------------------------------------------------------
// CONFIGURATION CACHE [BEN1-16] — the open decision this feature was assigned.
//
// Outcome: NOTHING NEEDED. me.champeau.jmh 0.7.3 is configuration-cache compatible on
// this build. gradle.properties keeps `org.gradle.configuration-cache=true` repo-wide
// and is untouched by this change; no `notCompatibleWithConfigurationCache` opt-out, no
// alternative plugin, no hand-rolled JMH invocation was required.
//
// Measured 2026-08-17, not inferred from the plugin's release notes: `:bench:build` run
// twice in a row reported `Configuration cache entry stored.` on the first run and
// `Configuration cache entry reused.` on the second, with zero configuration-cache
// problems reported in either.
//
// The guard task above is written to keep it that way — it captures a
// Provider<RegularFile> and Task.logger, and never reaches for `project` from an
// execution-time action, which is the usual way a hand-written task loses the cache.
// -----------------------------------------------------------------------------------

// -----------------------------------------------------------------------------------
// THE JAR'S OWN BUILD PROVENANCE (`computenet-7doz`).
//
// `UnitAttestation.harnessSha` (FloorDerivationLedger.kt) records the working tree's HEAD
// at the moment a unit was MEASURED — read by `derive-class-floor.sh` immediately before
// the JMH invocation. What it never witnessed is `bench-jmh.jar` itself: the jar is built
// ONCE, outside the gate, by `:bench:jmhJar`, and nothing stamped its provenance into it —
// so a rebuild at the same checkout was invisible, and a STALE jar carried across a
// checkout change was recorded under the new checkout's sha, attesting a commit the
// measured code did not come from. This closes that: `jmhJar`'s own manifest now carries
// the commit it was built from, written by the build itself rather than supplied by a
// caller, and `FloorTool.HarnessJarStamp` (FloorTool.kt) reads it back at ingest.
//
// The sha is read via `providers.exec`, Gradle's configuration-cache-safe process
// provider — captured here as a lazy `Provider<String>` and handed to the manifest AS A
// PROVIDER, never `.get()`-ed by this script. (`.get()` inside a `doFirst` was the first
// attempt and is rejected by the configuration cache; the `jmhJar` block below records
// why, and this paragraph used to describe that rejected form as if it were the landed
// one — computenet-7doz review.) It still honours the "capture a Provider, never reach
// for `project` at execution time" rule the guard task above documents, because the
// Provider — not the script — is what resolves.
//
// The value is resolved when `jmhJar` runs, not when the config cache entry was stored,
// so the manifest carries whatever commit is checked out at the moment `jmhJar` actually
// RUNS. MEASURED on darwin/arm64, 2026-08-27, with the configuration cache reused rather
// than rebuilt: stamp `42ebbe59c` at HEAD `42ebbe59c`; HEAD moved to `979832512`;
// `./gradlew :bench:jmhJar --rerun` printed "Configuration cache entry reused" and the
// stamp became `979832512`. A plain `./gradlew :bench:jmhJar` at the same moment reported
// `jmhJar UP-TO-DATE` and the jar kept its existing stamp — so if `jmhJar` is not
// re-executed after a checkout change (up-to-date, task caching, or simply not
// re-invoked), the jar on disk keeps its old, still-correct-for-itself stamp, which is
// exactly the staleness `floorTool ingest` is meant to make visible, not paper over.
//
// `Harness-Commit-Sha` is a plain manifest attribute name, not a constant shared with
// Kotlin source across the build-script/main-source boundary (a build script cannot
// depend on `:bench`'s own compiled output) — `FloorTool.HarnessJarStamp.MANIFEST_ATTRIBUTE`
// on the reading side must be kept equal to the literal here by hand; both sides carry a
// comment saying so.
val harnessCommitSha: Provider<String> = providers.exec {
    commandLine("git", "-C", rootDir.absolutePath, "rev-parse", "--short", "HEAD")
}.standardOutput.asText.map { it.trim() }

tasks.named<Jar>("jmhJar") {
    // Passed as the Provider itself, not `.get()` — `Manifest.attributes` accepts a
    // Provider value and resolves it lazily at execution time, which is what keeps this
    // configuration-cache safe. A `doFirst { manifest.attributes(... to harnessCommitSha.get()) }`
    // was tried first and rejected by the configuration cache ("cannot serialize Gradle
    // script object references"): a lambda literal at this script's top level captures the
    // enclosing script object even when it only reads a local val, and `Task.doFirst` closures
    // are exactly where the config-cache serializer inspects for that. Setting the attribute
    // through the DSL at configuration time, with the Provider as the value, needs no lambda
    // and avoids the capture entirely.
    manifest.attributes(
        // Must match `FloorTool.HarnessJarStamp.MANIFEST_ATTRIBUTE` verbatim.
        "Harness-Commit-Sha" to harnessCommitSha,
    )
}

// -----------------------------------------------------------------------------------
// THE REGRESSION-TRACKING SERIES' COMMAND-LINE FACE (computenet-b7k4).
//
// `civictech.bench.series.SeriesTool` ingests one JMH run's artifacts and compares its
// rows against each benchmark's own stored history. It runs over `main`'s runtime
// classpath, which is why it is a plain JavaExec and not the `application` plugin: this
// module already has three source sets and exactly one reason to produce a binary, and
// the `application` plugin would wire `run`, `distZip`, `installDist` and friends into
// the lifecycle for a tool that is invoked by hand and by a local scheduler.
//
// DELIBERATELY OUTSIDE `check`, `build` AND `test`, exactly as `:bench:jmh` and
// `:bench:jmhJar` are [BEN1-01]. Nothing in this file makes it a dependency of any
// lifecycle task, so it is unreachable from `./gradlew test`, from `:bench:build`, and
// therefore from every required CI check. The tool itself only READS the JMH artifacts
// a separate run produced — it launches no benchmark and forks no JVM of its own — so
// even a caller who wired it into `check` by mistake would not have made a required
// check run a benchmark. That is defence in depth, not the guarantee; the guarantee is
// that no task depends on it.
//
// Arguments arrive through the `seriesArgs` Gradle property, read via `providers` so it
// is a declared input rather than a configuration-time `project` read (see the
// CONFIGURATION CACHE note at the end of this file for why that matters here):
//
//   ./gradlew :bench:benchSeries -PseriesArgs="compare --results ... --series ..."
//
// scripts/bench-series/run-series.sh is the intended caller and documents the full
// invocation; `-PseriesArgs="--help"` prints the usage.
tasks.register<JavaExec>("benchSeries") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description =
        "Compares a JMH run against the stored regression series and optionally appends " +
            "it (computenet-b7k4). Not wired into check/build/test."
    mainClass.set("civictech.bench.series.SeriesToolKt")
    classpath = sourceSets["main"].runtimeClasspath

    // Pinned to the same toolchain the module compiles and benchmarks under. Gradle
    // would default a JavaExec to the `java` extension's toolchain anyway, but the
    // series exists precisely to keep a JDK from changing underneath a comparison, so
    // the pin is stated rather than inherited.
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) },
    )

    // Arguments arrive as one whitespace-separated `seriesArgs` property. Read through
    // `providers` and captured as a Provider — not a configuration-time `project` read —
    // so the task stays configuration-cache compatible, the property becomes a declared
    // input, and `-PseriesArgs=...` changing does not silently replay a previous run.
    // Every argument the tool takes is a flag or a path under a run directory this
    // repository controls, so splitting on whitespace is sufficient and no quoting
    // convention is invented here.
    val seriesArgs = providers.gradleProperty("seriesArgs").orElse("--help")
    argumentProviders.add(
        CommandLineArgumentProvider {
            seriesArgs.get().split(Regex("\\s+")).filter { it.isNotBlank() }
        },
    )
}

// -----------------------------------------------------------------------------------
// THE CHECKPOINTED FLOOR DERIVATION'S COMMAND-LINE FACE (computenet-3omz.2).
//
// `civictech.bench.FloorTool` is the CLI face of `FloorDerivationLedger`
// (`computenet-3omz.1`): plan/next/ingest/status/render over a per-class derivation that
// may be completed across several short quiesced windows instead of one continuous
// stretch. Mirrors `benchSeries` immediately above for the same reasons — a plain
// JavaExec over `main`'s runtime classpath rather than the `application` plugin, and
// DELIBERATELY OUTSIDE `check`, `build` AND `test`: this tool only reads artifacts other
// invocations produced (a JMH results file, a run log) and launches no benchmark and
// forks no JVM of its own, except `plan`'s and `next`'s use of the built jar to read its
// own `-lp` enumeration and its classes' `@Fork`/`@Warmup`/`@Measurement` annotations —
// neither of which runs a benchmark iteration. Nothing in this file makes it a dependency
// of any lifecycle task, so it is unreachable from `./gradlew test`, from `:bench:build`,
// and therefore from every required CI check.
//
// Arguments arrive through the `floorArgs` Gradle property, read via `providers` for the
// same configuration-cache reason `seriesArgs` is (see that task's comment):
//
//   ./gradlew :bench:floorTool -PfloorArgs="plan --ledger <dir> --class <Name> --jar <jar>"
//
// `-PfloorArgs="--help"` prints usage, and is also the default with no property set.
tasks.register<JavaExec>("floorTool") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description =
        "CLI face of the checkpointed per-class floor derivation ledger (computenet-3omz.2). " +
            "Not wired into check/build/test."
    mainClass.set("civictech.bench.FloorToolKt")
    classpath = sourceSets["main"].runtimeClasspath

    // Pinned to the same toolchain the module compiles and benchmarks under, for the same
    // reason `benchSeries` pins it: `plan`'s enumeration and `next`'s annotation reads run
    // this JVM against the built jar, and a JDK drifting underneath that read would be
    // exactly the defect the derivation's own single-JVM refusal exists to catch.
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) },
    )

    val floorArgs = providers.gradleProperty("floorArgs").orElse("--help")
    // Passed as ONE argument, deliberately. This used to split on whitespace here, which
    // made a `--jmh-config` value containing spaces impossible to express: the property is
    // a single string, so any quoting the operator wrote was already gone by the time this
    // ran, and `--jmh-config 'forks=1, warmup 3x1s'` was refused with
    // "expected a --flag, found 'warmup'" — while the template `derive-class-floor.sh`
    // prints shows exactly that field as free text (`computenet-71hu`). `FloorCli.run`
    // tokenises a single argument itself, honouring quotes, so the quoting survives.
    argumentProviders.add(CommandLineArgumentProvider { listOf(floorArgs.get()) })
}
