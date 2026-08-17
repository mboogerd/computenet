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
