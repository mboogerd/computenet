package buildsrc.convention

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.getByType

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// Shared test stack: every module that runs JUnit5 tests needs these. Modules that
// also depend on :testkit get JUnit/kotlin-test transitively (testkit exposes them
// as `api`), but declaring them here too is harmless and keeps modules that don't
// use :testkit (concord, wire, demo:shell, ...) self-sufficient.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(libs.findLibrary("kotest-assertions-core").get())
    "testImplementation"(libs.findLibrary("junit").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform").get())
    "testImplementation"(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        // Two-JVM/`ProcessBuilder` tests (`@Tag("multi-jvm")`) fork external `java`
        // processes and wait on fixed wall-clock awaits for OS-level process
        // startup + socket bind — starved hard by scheduler contention on a
        // constrained-core CI runner (T13 / doc/remediation/AUDIT-2026-07-28.md
        // §W1). Gated by project property so a plain `./gradlew test` is
        // unchanged (runs everything); CI passes one of these two, one per lane.
        when {
            project.hasProperty("multiJvmOnly") -> includeTags("multi-jvm")
            project.hasProperty("excludeMultiJvm") -> excludeTags("multi-jvm")
        }
    }
    // The whole kernel suite runs in one JVM fork; ProtocolSupport keys ports in a
    // JVM-global map whose handler-closure values reference their keys, so ports
    // created across the suite accumulate (a pre-existing structural retention the
    // per-node policy work nudged past a constrained default heap). 2g gives margin;
    // forkEvery bounds accumulation by periodically starting a fresh test JVM.
    //
    // Note what `forkEvery` actually counts: TEST CLASSES, not test methods. That is
    // Gradle's own unit here, not an inference from a log: the pipeline is
    // MaxNParallelTestDefinitionProcessor -> RestartEveryNTestDefinitionProcessor ->
    // ForkingTestDefinitionProcessor, the counter increments once per
    // `processTestDefinition`, and on the JVM the definition type is
    // `ClassTestDefinition(String testClassName)` — one unit, one test class. Only
    // `:kernel` has enough classes to reach 80 (202 today); `:inspect` has 28,
    // `:concord` 9, `:wire` 7 and every demo 6 or fewer, so this setting has never
    // forked those suites even once — the whole of `:inspect:test` runs in a single
    // JVM regardless of it. That is why computenet-4vh's leak measurement saw
    // `ManagedHost-` threads climb monotonically to 258 with no reset, and it also
    // means 4vh's proposed mechanism (adding a test re-partitions which `:inspect`
    // tests share a JVM with the 100k-entry paged-state walk) cannot occur: there
    // is only ever one partition. Measured 2026-08-10 (computenet-dqy.6): with the
    // resources released, `:kernel:test` costs 83.6s at forkEvery(80) and 83.4s at
    // forkEvery(0) — the extra JVM starts are not worth measuring, so this stays
    // where it is, as the kernel heap guard its comment above says it is.
    //
    // It composes with `maxParallelForks` below rather than being overridden by it:
    // MaxNParallel deals classes round-robin to N processors, each of which owns its
    // OWN restart counter, so `:kernel`'s 202 classes become 101 per processor and
    // each restarts once — 4 test JVMs where a single-fork run used 3. The bound the
    // heap guard cares about is unchanged: still at most 80 classes' accumulation in
    // any one JVM.
    maxHeapSize = "2g"
    setForkEvery(80)
    // `:kernel:test` is the whole wall time of the `build-test-fast` required check.
    // Measured from the CI logs of runs 31360185624 and 31370311748: 157s of a 314s
    // Gradle build, and it runs ALONE for the last ~139s of it — every other test
    // task, `:inspect:test` and `:demo:exchange:test` included, has finished by then,
    // so 3 of the runner's 4 vCPUs idle while the build waits on one JVM. The classes
    // that dominate it are deterministic seed sweeps (ShardedReplicaFrontierTest,
    // UnknownJoinerFenceTest, AlignedObserveTest: 100-200 seeds each), i.e. CPU-bound
    // work that spare cores turn directly into wall time. Locally, two forks: the task
    // goes 83.6s -> 64.0s standalone (repeated: 65.1, 65.5, 64.7) and 84.5s -> 64.1s
    // inside the whole fast lane, which takes the lane itself from 135.9s to 120.0s.
    // 955 kernel tests and 1449 lane tests green in every one of those runs, counted
    // from the JUnit XML. `:inspect:test` is unmoved either side (35.9s vs 36.8s),
    // which is the check that this opt-in is really scoped to one project.
    //
    // Then measured ON CI, which is the number that actually matters (PR #30, run
    // 31373109141, same log-attribution method as the baselines above, same full
    // cache miss — a buildSrc edit invalidates everything):
    //   `:kernel:test`        157.1s -> 125.2s   (960 tests, green)
    //   its solo tail          138s  ->   95s
    //   Gradle build           313s  ->  293s
    //   build-test-fast job    336s  ->  312s    (-7%; the 6m03s baseline was 363s)
    // So the task-level projection held and the job-level one was slightly optimistic:
    // one fewer core-second of idle tail does not convert 1:1 into job time, because
    // runner setup, the cold Kotlin daemon (computenet-dqy.15) and the compile chain
    // are untouched. The remaining 95s tail is computenet-dqy.16's lever, not this one's.
    //
    // `:kernel` alone opts in, deliberately. The socket-bound suites (`:wire`,
    // `:inspect`, `:demo:shopping`) are this repo's known flake sites, and running
    // their forks concurrently would put them in competition for ports — the class of
    // defect PR #22 fixed. They would also buy nothing: they are already fully
    // overlapped by the kernel compile+test chain and contribute zero wall time.
    // `kernel/src/test` binds no sockets at all (`ProtocolSupport.bind` is a cell
    // port, not a TCP one), it reaches nothing socket-bound through `:testkit` either
    // (it imports only SimWorld, awaitUntil and forEachSeed — never JvmPeer, whose
    // `freePort()` is the one racy allocator in there, nor HttpProbe), it sets no
    // system properties, and it has no `@Tag("multi-jvm")` class to exclude in the
    // first place — those all live in `demo/shopping` and `demo/exchange`. Its only
    // filesystem use is `@TempDir` and `Files.createTempDirectory`, both of which
    // hand out a fresh path per call, so two forks cannot collide on one.
    //
    // Half the cores, capped at 2: Gradle's worker-lease pool already bounds total
    // concurrency to `--max-workers`, and each fork may grow to `maxHeapSize` above,
    // so 2 is the largest value that leaves a 4-vCPU/16g runner room for the daemons
    // and keeps the 30s `awaitUntil` budgets clear of starvation. Four forks measured
    // faster still (50.4s) on a 10-core dev machine; that is not the machine CI runs
    // on and the extra 4g of committed heap is not worth the flake exposure.
    //
    // `availableProcessors()` is read at configuration time and therefore baked into
    // the configuration-cache entry rather than re-read on reuse. The clamp is what
    // makes that harmless: every machine in play has >= 4 cores, so the value is 2
    // either way, and the floor of 1 covers a genuinely small machine.
    //
    // Residual risk, stated rather than hidden: a class is never split across forks,
    // so per-class statics are unaffected, but round-robin dealing changes WHICH
    // classes share a JVM. A test that depended on state another class left behind
    // would surface here. Nothing in `kernel/src/test` declares `@BeforeAll` or an
    // execution order, and the config has now been run 6x over the full suite
    // (960 tests, 0 failures each, from the JUnit XML) plus 10x over the
    // timing-sensitive host/observe classes, all green, on top of the CI run above.
    if (project.path == ":kernel") {
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 2)
    }
    // Hang -> failure, not a silently stuck build. 440+ unbudgeted runToIdle() call
    // sites and zero prior @Timeout meant a livelock regression could hang CI
    // indefinitely. 5 minutes is deliberately generous (seed sweeps); raise per-class
    // with @Timeout if a legitimate test needs more, don't raise this default.
    systemProperty("junit.jupiter.execution.timeout.testable.method.default", "5m")
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED
        )
        // SHORT (the default) prints only the exception class name — CI's log
        // for a failing test carried no message, no stack, nothing to diagnose
        // from. FULL is the whole point of a CI log existing.
        exceptionFormat = TestExceptionFormat.FULL
    }
}
