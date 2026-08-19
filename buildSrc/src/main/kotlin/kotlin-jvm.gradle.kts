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
        // @Tag("bench") gate (BEN1, computenet-x9e.2.1) [BEN1-09][BEN1-10][BEN1-11].
        // Deliberately a SEPARATE conditional from the multi-jvm `when` above, not a
        // shared arm of it: JUnitPlatformOptions.includeTags/excludeTags each accumulate
        // into their own Set (calling them again ADDS tags, it does not replace), so two
        // independent conditionals compose into one filter rather than the first match
        // in a combined `when` shadowing the second. That is what makes
        // `-PexcludeMultiJvm=true` exclude multi-jvm AND bench together below, instead
        // of only whichever arm happened to come first.
        //
        // Bench differs from multi-jvm in its DEFAULT: multi-jvm runs everything unless
        // told otherwise; bench is excluded unless `-PbenchOnly` explicitly asks for it.
        // That asymmetry is deliberate and is the refutation of the "permanent tax"
        // objection to adding a benchmark module at all (V1C-BENCH) — a plain
        // `./gradlew test`, and every module's default `test` task, NEVER executes a
        // `@Tag("bench")` probe. `-PbenchOnly=true` flips to the opposite extreme: only
        // bench-tagged tests run, and the unconditional exclusion above does not apply
        // (an include and an exclude of the same tag would be contradictory, so this is
        // an if/else, not two independent calls the way multi-jvm composes with bench).
        if (project.hasProperty("benchOnly")) {
            includeTags("bench")
        } else {
            excludeTags("bench")
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
    // (it imports only SimWorld, awaitUntil and forEachSeed — never JvmPeer, which
    // launches peer JVMs and, since computenet-dqy.25, lets each one bind its own
    // ports rather than allocating any here, nor HttpProbe), it sets no
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
    //
    // ---------------------------------------------------------------------------
    // `:demo:beadsmirror` opts in SECOND (computenet-9vx3), and the count is
    // machine-dependent on purpose: 2 on CI's 4-vCPU runner, 4 on a >=8-core dev box.
    // The proposal was that this module is `:kernel`'s inverse workload — 295 tests
    // whose wall time is a test thread BLOCKED on a `bd`/`dolt` child process, so a
    // fork parked in `Process.waitFor` holds no core, the JVM+child pair counts as
    // one runnable unit, and the half-cores rule that is right for `:kernel`
    // under-subscribes here. That model predicted 4 forks everywhere (Gradle's
    // worker-lease pool caps test forks at `--max-workers`, i.e. the runner's 4
    // vCPUs, so 4 looked like a ceiling rather than a guess). IT IS ONLY TRUE WITH
    // CORES TO SPARE. `dolt` is a Go binary that uses the whole machine and commits
    // through fsync, so a `bd` mutation is not idle waiting — it is the work, merely
    // happening in another process and against the disk.
    //
    // Why it was worth doing at all, from CI rather than a dev box. In
    // `build-test-fast` run 32283729126 (head 28e29924, `main`'s content),
    // reconstructing the task timeline from the streamed `> Task :…` headers:
    // `:demo:beadsmirror:test` spans 270.3s, and every OTHER task in the lane —
    // `:demo:exchange:test`, `:demo:slotfinder:test`, the whole compile chain — has
    // finished 14s in. So the final 256.0s of the required check was this ONE test
    // JVM, three of the runner's four vCPUs idle and all four worker leases free.
    // Same shape as the `:kernel` paragraphs above, and now the fast lane's whole
    // tail (`:kernel:test` moved to its own `kernel-test` job and is `-x`-ed here).
    //
    // MEASURED ON A QUIET 16-CORE DEV MACHINE, `:demo:beadsmirror:test --rerun
    // -PexcludeMultiJvm=true`, fork count injected by init script so the tree is
    // byte-identical across arms, two trials each, interleaved 1/2/4/1/2/4 so drift
    // cannot masquerade as an effect:
    //
    //   forks    trial 1   trial 2     mean    speedup
    //     1       780.0s    760.1s    770.1s     1.00x
    //     2       497.8s    480.5s    489.2s     1.57x
    //     4       316.4s    329.0s    322.7s     2.39x
    //
    // Every one of the six runs: 45 JUnit XML files, 295 tests, 0 failures, 0 errors,
    // 0 skipped. The totals are what make the wall-clock numbers mean anything — a
    // fork change is exactly where a class can silently stop running.
    //
    // ON CI THE ANSWER IS DIFFERENT, AND CI IS TOO NOISY TO READ FROM WALL TIME.
    // `:demo:beadsmirror:test` spans from the task timeline of five `build-test-fast`
    // runs, alongside the sum of the classes' own occupied time (from the streamed
    // per-test lines — it isolates contention from overlap) and the span of the rest
    // of the lane's test events, which is the control for how fast that runner was:
    //
    //   forks   module span   sum of class spans   rest of lane   run
    //     1        270.2s        189.6s               98.5s       32283729126
    //     2        144.5s        200.5s               67.8s       32292718836
    //     2        219.3s        283.2s               99.6s       32293886125
    //     2        445.6s        517.0s               67.0s       32294701932 att.1
    //     2        226.7s        294.8s              104.5s       32294701932 att.2
    //     4        273.3s        591.4s              258.3s       32291870198
    //
    // The last two 2-fork rows are THE SAME COMMIT rerun, and they differ by 2.0x.
    // That is the noise floor of a GitHub runner for this module — `dolt`'s fsync
    // traffic makes it disk-bound in a way the rest of the lane is not, which is why
    // the control column barely moves while the module column doubles. So do NOT
    // conclude anything here from one run's job duration, in either direction; a
    // single 10m28s `build-test-fast` on this branch was chased down to exactly this
    // and is row 4 above.
    //
    // What IS readable on CI is the last row's control column. At 4 forks the REST
    // OF THE LANE inflated to 258.3s against 67-105s in every other run: four test
    // JVMs plus their `bd`/`dolt` children starve the other modules' suites on a
    // 4-vCPU runner, and the module's own sum-of-class-spans triples (591.4s, with
    // ReadyDifferentialTest going 47.9s -> 193.5s). That signature appears in no
    // other run and is not a wall-clock claim, so it survives the noise. Four forks
    // on this runner is not a smaller win than two; it is a tax on everyone else.
    //
    // Hence `availableProcessors() / 2` clamped to [1, 4]: 2 on the 4-vCPU runner,
    // where 2 left the control column alone across four runs and 4 tripled it in the
    // one run it got; 4 on a >=8-core machine, which is where the 2.39x was measured. The clamp is a
    // floor on evidence, not on generosity — 4 is the largest count measured
    // anywhere, so nothing here extrapolates past its data.
    //
    // Safety, audited per class rather than assumed, since concurrent forks are the
    // condition under which computenet-dqy.25's defect family bites:
    //  * PORTS. Nothing in this module ever names a port it has not already bound.
    //    `MirrorRoutesTest` uses `DemoShell(0)` and reads `boundPort`;
    //    `BeadsMirrorAppTest` takes `BeadsMirrorConfig.port`'s default of `0` and
    //    reads `app.boundPort`; `TwoNodeRig` starts its listener with
    //    `MirrorWire.Listen(0)` and hands the DIALER `app.boundWsPort`, the port the
    //    listener actually bound; `MirrorPeeringTest.BoundPort` binds `Listen(0)`
    //    in-process and asserts the announced port is the bound one, while its other
    //    cases only PARSE flags (`"--listen", "0"`, `ws://localhost:9001`) and open
    //    nothing; `TwoJvmMirrorTest` passes `0` for every port and reads each child's
    //    announced `computenet-port` line through `JvmPeer`. `testkit` carries no
    //    `freePort()` any more (dqy.25 deleted it) and `grep -rn 'ServerSocket'` over
    //    this module's tests returns nothing. Every bind is `bind(0)` performed by
    //    the process that then KEEPS the socket, so there is no window in which a
    //    chosen port is unowned — which is the only thing concurrent forks could have
    //    exploited. `TwoJvmMirrorTest` is the module's one `@Tag("multi-jvm")` class,
    //    excluded from this lane anyway and run by the serial lane at
    //    `--max-workers=1`.
    //  * WORKSPACE ISOLATION is computenet-s5hx's, and is what unblocked this at all:
    //    every test's workspace is a COPY with a unique basename, a rehomed embedded
    //    Dolt database and therefore a distinct `DotMinter` source id, under its own
    //    `Files.createTempDirectory` path. `BdScratchWorkspace.templates` is a
    //    per-JVM `ConcurrentHashMap`, so each fork simply builds its own pristine
    //    template (one `bd --sandbox init` per fork per `bd` env) — duplicated work,
    //    not shared state, and part of what the +5.7% above is paying for.
    //  * ROUND-ROBIN DEALING. A class is never split, so the class-scoped
    //    `@BeforeAll`/`@TestInstance(PER_CLASS)` fixtures (`TwoNodeRigTest`,
    //    `PullRebaselineTest`, `HeadlineLivenessTest`) are unaffected. The module's
    //    only cross-class statics are that template cache and two stateless
    //    `object`s, `MirrorExportEquality` and `ReadySchedule`.
    //
    // What is NOT proven: flake behaviour over many runs. This rests on 6 local runs
    // and 5 CI runs, all green, all with identical totals — enough to say the setting
    // does not break the suite, not enough to characterise a rare race.
    // `TwoNodeRig`'s waits are a 30s budget over a 200ms poll interval, so starvation
    // is the failure mode to watch. If this lane starts timing out in convergence
    // awaits rather than failing assertions, drop the clamp to 1 and measure again
    // before widening any budget.
    when (project.path) {
        ":kernel" -> maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 2)
        ":demo:beadsmirror" -> maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
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
    //             ...
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
    // 3. THE TIMEOUTEXCEPTION'S OWN STACK CAN NEVER NAME THE BLOCKED FRAME. The exception
    //    is constructed inside the `finally`, on the interceptor's own stack — those two
    //    `java.util.ArrayList.forEach` frames CI printed. That trace is evidence about
    //    nothing, in either direction. Do not read it as a location. It does NOT follow
    //    that the RECORD names nothing: the XML around it usually does — see the
    //    discriminator below, which is the part computenet-dqy.2 actually needs.
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
    // AND THE XML OFTEN NAMES THE BLOCKED FRAME ANYWAY. This is the corollary of (3) that
    // matters, and the reason (3) is a statement about ONE STACK and not about the record
    // as a whole. Two channels, both already armed, both measured against a probe class
    // run in :inspect itself (review of computenet-dqy.12):
    //
    // - THE SUPPRESSED InterruptedException. If the test thread was in an INTERRUPTIBLE
    //   wait, the interrupt makes that wait throw, the throw propagates out of
    //   `delegate.proceed()`, and it reaches the suppressed slot by exactly the mechanism
    //   in (2) — so the demotion that hides a failure from the console is the same thing
    //   that carries the blocked frame into the XML:
    //
    //       Suppressed: java.lang.InterruptedException: sleep interrupted
    //           at java.base/java.lang.Thread.sleep(Thread.java:509)
    //           at ...ReviewTimeoutXmlProbe.reviewProbeBlockedFrameMarker(...kt:20)
    //
    // - THE PRE-INTERRUPT THREAD DUMP, `:inspect` only. It is written at the deadline,
    //   just BEFORE the interrupt, so it captures the thread where it actually sat:
    //   `"Test worker" prio=5 Id=1 TIMED_WAITING` over the probe's own frame. Platform
    //   threads only (Thread.getAllStackTraces()), so a parked ManagedHost virtual thread
    //   remains invisible — that still needs jcmd Thread.dump_to_file.
    //
    // Which makes the three hypotheses separable from ONE failed run's artifact:
    //   suppressed InterruptedException present -> blocked in the JVM, at the named frame
    //   absent, dump shows Test worker inside the test body -> blocked uninterruptibly
    //   absent, dump shows Test worker in framework frames  -> the body had ALREADY
    //       FINISHED; this is the (1) case, and the external-freeze candidate is live
    // Verified by construction, not inferred: three probes — one blocked interruptibly,
    // one that swallowed the interrupt and then threw, one that swallowed it and RETURNED
    // NORMALLY — produced three distinguishable `<failure>` bodies while all three printed
    // the identical bare `TimeoutException ... at ArrayList.forEach` to the console.
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
    //    In :inspect — the module with the open hang — 18 of the 28 test classes bind an
    //    InspectorServer and/or a VirtualThreadScheduler and release them in @AfterEach,
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
    // changing the default for every test in the repo. For InspectorErrorsTest
    // (computenet-dqy.2) specifically, read the next failed run's `test-results-fast` XML
    // FIRST: under SAME_THREAD that artifact already carries both channels above, so the
    // annotation would buy a frame that is very likely already there while dropping any
    // failure coincident with the deadline. Reach for it only if a real occurrence comes
    // back with neither channel populated.
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
