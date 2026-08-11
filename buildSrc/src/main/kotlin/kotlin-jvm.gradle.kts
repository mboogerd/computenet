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
    // Then measured ON CI, which is the number that actually matters. Two green
    // build-test-fast runs on PR #30, against the two baselines above:
    //   `:kernel:test`   157.1s -> 125.2s and 124.6s   (960 tests green both times)
    //   whole job         336s and 363s -> 312s and 331s
    // Read those two rows differently, because they are not equally trustworthy. The
    // task figure is derived from streamed per-test log lines, it reproduced to within
    // 0.6s, and it is a clean 1.26x. The job figure is noisy at the +/-20s level: how
    // much of the 32s shows up depends on where the kernel compile chain happens to
    // land relative to the other modules, and in one of the two runs `:kernel:test`
    // started only after every other test task had already finished. So the honest
    // claim is "the task is reliably ~32s faster, the job is somewhere between 5s and
    // 25s faster", not a headline percentage. Runner setup, configuration (12.7s, and
    // the configuration cache misses on CI every run — computenet-aer) and the compile
    // chain are all untouched by this knob; the remaining tail is computenet-dqy.16's.
    //
    // Caveat on the profile that motivated this, worth knowing before trusting the
    // rest of that table: in a non-tty log Gradle prints `> Task :x` with the task's
    // FIRST OUTPUT, so a task that is silent at lifecycle level gets its header at
    // completion, not at start. Spans for the TEST tasks are unaffected — they are
    // computed from streamed per-test lines, and both load-bearing facts here (this
    // task's last test event coincides with BUILD SUCCESSFUL, and the last non-kernel
    // test event precedes it by 138s) are streamed at both ends. The KSP and compile
    // rows of that profile are NOT reliable and should not be requoted.
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
    //
    // ---------------------------------------------------------------------------
    // HOW TO READ A TIMEOUT THIS SETTING PRODUCED (computenet-dqy.12). Three things
    // that are not obvious, and that a hang investigation gets wrong by default.
    //
    // The thread mode is JUnit's default, SAME_THREAD (kept deliberately — see below),
    // so every testable method is routed through
    // org.junit.jupiter.engine.extension.SameThreadTimeoutInvocation. Read off the
    // 5.14.2 sources jar; the class is unchanged in the 5.13.4 engine this repo
    // resolves. Its proceed(), elided to the load-bearing lines:
    //
    //     try { result = delegate.proceed(); }
    //     catch (Throwable t) { failure = t; }
    //     finally {
    //         ...
    //         if (interruptTask.executed) {
    //             Thread.interrupted();
    //             failure = TimeoutExceptionFactory.create(desc, timeout, failure);
    //         }
    //     }
    //     if (failure != null) { throw failure; }
    //
    // 1. A TimeoutException DOES NOT MEAN THE METHOD FAILED. The `interruptTask.executed`
    //    branch is in the `finally` and is not guarded by whether `delegate.proceed()`
    //    threw. If the scheduled interrupt ran at all, the invocation throws
    //    TimeoutException even for a method that completed successfully. Measured with a
    //    probe that slept past its deadline, swallowed the interrupt and RETURNED
    //    NORMALLY: reported FAILED, with `TimeoutException ... at ArrayList.forEach` and
    //    nothing else. That is exactly the CI signature of the undiagnosed 5-minute
    //    InspectorErrorsTest stall (computenet-dqy.2 / 8ru.3), so that signature does not
    //    establish that the test thread was blocked in the JVM at all — an external
    //    whole-VM freeze (hypervisor steal, live migration, total I/O stall) produces
    //    identical output, and is invisible from inside the build log.
    // 2. A REAL FAILURE IS DEMOTED TO A SUPPRESSED EXCEPTION. TimeoutExceptionFactory
    //    .create(sig, duration, failure) does `timeoutException.addSuppressed(failure)`.
    //    An assertion failure or bounded-wait miss that merely coincided with the
    //    deadline survives only as a suppressed section, and Gradle's console stack
    //    filter drops suppressed sections unconditionally (computenet-8ru.4).
    // 3. THE STACK CAN NEVER NAME THE BLOCKED FRAME. The exception is constructed inside
    //    the `finally`, on the interceptor's own stack — those two
    //    `java.util.ArrayList.forEach` frames CI printed. The trace is evidence about
    //    nothing, in either direction. Do not read it as a location.
    //
    // SO READ A TIMEOUT OUT OF THE XML, NEVER THE CONSOLE. `build/test-results/**/TEST-*
    // .xml` DOES retain the suppressed section — established by running the probe above
    // and reading the file, not inferred:
    //
    //     <failure message="java.util.concurrent.TimeoutException: probeTimesOutAndFails()
    //              timed out after 1 second" type="java.util.concurrent.TimeoutException">
    //     java.util.concurrent.TimeoutException: probeTimesOutAndFails() timed out after 1 second
    //         at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
    //         at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
    //         Suppressed: java.lang.AssertionError: PROBE-REAL-FAILURE-MARKER: this is the real failure
    //             at TimeoutXmlProbe.probeTimesOutAndFails(TimeoutXmlProbe.kt:22)
    //             at java.base/java.lang.reflect.Method.invoke(Method.java:580)
    //             ... 2 more
    //     </failure>
    //
    // The same run's console showed only the TimeoutException and its two forEach
    // frames. ci.yml uploads these XMLs whenever a job does not succeed
    // (computenet-8ru.4), and :inspect additionally arms PreInterruptThreadDumpPrinter
    // into <system-out> (inspect/src/test/resources/junit-platform.properties).
    //
    // THREAD MODE STAYS SAME_THREAD, AS A DECISION (computenet-dqy.12), not as an
    // unexamined default. `junit.jupiter.execution.timeout.thread.mode.default =
    // SEPARATE_THREAD` is genuinely tempting: it routes through
    // assertTimeoutPreemptively, so (3) goes away — the TimeoutException gets an
    // ExecutionTimeoutException cause whose stack is `thread.getStackTrace()` of the
    // blocked thread (measured: `Execution timed out in thread junit-timeout-thread-2`
    // over the probe's own frame) — and (1) goes away too, since the timeout is decided
    // by `future.get(timeout)`, which returns the value if the task completed. Rejected
    // anyway, for two reasons that outweigh a better stack:
    //
    // a. IT LOSES THE REAL FAILURE INSTEAD OF DEMOTING IT. The identical probe rerun
    //    under SEPARATE_THREAD reported the TimeoutException with NO trace of the
    //    AssertionError anywhere in the XML — the thread is abandoned at the deadline
    //    and whatever it throws afterwards goes nowhere. That makes (2) strictly worse:
    //    a suppressed failure is recoverable from the XML, a dropped one is gone. This
    //    repo's whole failure-accounting posture is that no path silently drops a
    //    failure; trading that away for a stack trace is the wrong direction.
    // b. IT ABANDONS A LIVE TEST BODY INTO THE REST OF THE SUITE. assertTimeoutPreemptively
    //    only calls `executorService.shutdownNow()`, which interrupts; it cannot stop a
    //    thread that does not honour the interrupt, and the suite proceeds regardless.
    //    In :inspect — the module with the open hang — every test class binds an
    //    InspectorServer and a VirtualThreadScheduler and releases them in @AfterEach,
    //    and forkEvery never forks that suite (28 classes, one JVM), so a single hang
    //    would close a server and shut a scheduler down underneath a body still running
    //    inside it, and take the remaining 27 classes with it. One undiagnosed hang
    //    becomes a cascade of correlated failures with no obvious first cause.
    //
    // The thread-affinity objection, checked rather than assumed, turns out to be the
    // WEAK one and should not be the reason quoted: there is no @BeforeEach or
    // @BeforeAll anywhere in the repo (the only lifecycle methods are 18 @AfterEach, all
    // in :inspect), and the five kernel tests that assert about threads compare
    // `Thread.currentThread()` against itself within one method body, so they do not
    // care which thread that is — all 28 of their tests pass under SEPARATE_THREAD. The
    // bare ThreadLocals in MessageContext/Identity/Invocation are likewise set and read
    // within a single body today. But that is a property of the code as it stands, which
    // nobody maintains deliberately, so flipping the default would silently make "the
    // body runs on the thread its lifecycle callbacks run on" a rule every future test
    // must respect. Also measured, and reported for what it is: one full :inspect run
    // under SEPARATE_THREAD came back 259/262 with three assertion failures
    // (InspectorActivityTest, InspectorColdTest x2) that do NOT reproduce when those
    // classes run in isolation under either mode — taken at load average ~118 on a
    // shared machine, so it attributes to nothing. It does show the shape of the bill: a
    // repo-wide mode flip needs a clean full-suite evaluation, for a diagnostic that
    // helps only if the hang recurs.
    //
    // If one class needs the blocked frame, scope it to that class —
    // `@Timeout(value = 5, unit = MINUTES, threadMode = SEPARATE_THREAD)` — rather than
    // changing the default for every test in the repo.
    // ---------------------------------------------------------------------------
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
