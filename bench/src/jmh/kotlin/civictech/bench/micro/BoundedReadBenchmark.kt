package civictech.bench.micro

import civictech.bench.HostFacts
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * **V1C-BENCH E1, re-expressed**: what one whole-state copy of a `SetCell` costs, in
 * isolation, at 10^3 / 10^4 / 10^5 elements (`[BEN1-22]`, BS-15).
 *
 * The original E1 (`doc/spec/90-roadmap/98-inspector-v4-plan/30-bounded-read-measurement.md`
 * §3) timed two things: `Stateful.snapshot()` called directly on an unhosted cell, and
 * `ManagedHost.snapshotOf(ref).get()` end to end (submit + dequeue + copy + future
 * completion) on a real threaded host. [realDirect] and [realHostedSnapshotOf] are those
 * two — named `direct` and `hostedSnapshotOf` until computenet-7w4e, which is the name the
 * already-published E1 entries in `doc/bench/findings.md` carry, so a reader arriving from
 * one of those entries knows which method it means. See [realDirect] for why the drive has
 * to be in the name at all. The
 * split is the load-bearing part — §3's reading is that "`snapshotOf()`'s end-to-end
 * cost is not meaningfully larger than the bare `snapshot()` call it wraps", which is a
 * claim about the *difference* between these two numbers and cannot be re-derived from
 * either alone.
 *
 * ## Why E1 is JMH and E2/E3 are not
 *
 * E1's subject is a single, short, side-effect-free call whose input state does not change
 * as it is measured — the textbook shape for a JMH microbenchmark, and the shape that
 * benefits most from JMH's fork isolation, warmup accounting and error bars (the original
 * hand-rolled 20 warmup + 30 timed reps and reported median/p95 by hand). E2 and E3 are
 * not that: their observable is a *stall in a third cell's arrival stream* while two
 * threads contend for one host, which JMH has no vocabulary for. They are therefore
 * `@Tag("bench")` JUnit probes — see `BoundedReadProbeTest`, which records the judgment
 * the feature left open and the reasons it went that way.
 *
 * ## What is NOT measured here, and must not be inferred
 *
 * - **Allocated bytes per call.** The original reported ~270 KB / 2.61 MB / 26.9 MB per
 *   direct call from `ThreadMXBean.getThreadAllocatedBytes`, and reported the `snapshotOf`
 *   column as `—` because attributing allocation to the host's own virtual thread needed a
 *   marker cell to identify it. Nothing here counts allocation: the sanctioned way to get
 *   it for a JMH benchmark is `-prof gc`, which is a *run-time* flag, not a source change,
 *   and the sibling sweep task chooses whether to pass it. A source-level allocation
 *   counter would also be wrong under JMH, which runs the body many times per iteration.
 * - **GC counts inside the timed window.** §8's leading finding is that G1 young
 *   collections, not the copy loop, set the 10^5 tail (5-7 collections inside a
 *   30-call window; p95 at 4-5x the median). JMH reports a per-iteration error rather than
 *   a p95, so the *shape* of that finding is not directly reproducible from this
 *   benchmark's default output. `-prof gc` recovers the allocation rate that causes it.
 *   **A missing tail here is therefore not evidence the tail is gone.**
 * - **Anything about paging.** E1 is the numerator of §6's "total-work premium"
 *   comparison; E3's summed page time is the other half, and it lives in the probe.
 *
 * ## Running it
 *
 * ```
 * ./gradlew :bench:jmhJar
 * ~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java \
 *      -jar bench/build/libs/bench-jmh.jar BoundedReadBenchmark \
 *      -rf csv -rff /abs/path/e1.csv 2>&1 | tee /abs/path/e1.log
 * ```
 *
 * **Invoke the Gradle toolchain's own JDK 21 by path, never a bare `java`.** `NOISE_FLOOR`
 * (`civictech.bench.Dispersion`) was derived on Temurin 21 and every other entry in
 * `doc/bench/findings.md` is measured there, so a sweep on another JVM is not comparable
 * to the threshold it will be classified against. A bare `java` resolved to Homebrew
 * JDK 26 on the machine this was run on — the exact substitution that produced the
 * superseded REAL-drive throughput entry (`computenet-hqid`), where five major JDK
 * versions separated the claimed environment from the measured one. Nothing in this build
 * pins it for you: the JMH jar is an ordinary executable jar and Gradle is not in the
 * loop once it is built. The run's own `# VM version:` banner line, retained in the
 * `.log` beside the results, is the check that the right JVM took — read it, do not
 * assume it.
 *
 * The `tee` is not optional if the results are going to become a findings entry: JMH's CSV
 * carries no JVM columns, so `civictech.bench.MeasuringJvm.fromJmhLog` reads the measuring
 * JVM off the banner in that log and refuses without it (computenet-hqid) — and
 * `civictech.bench.HostFacts.fromJmhLog` reads [DirectState.announceHost]/
 * [HostedState.announceHost]'s CPU/core/OS banner off that very same log, refusing
 * likewise (computenet-yhbd, wired into this class by computenet-7w4e).
 *
 * A single-combination smoke run, to prove the harness measures at all — **on the same
 * pinned JDK, for the reason the paragraph above gives**:
 *
 * ```
 * ~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java \
 *      -jar bench/build/libs/bench-jmh.jar 'BoundedReadBenchmark.realDirect' \
 *      -p scale=N1E3 -f 1 -wi 1 -i 1 -rf csv -rff /tmp/e1-smoke.csv
 * ```
 *
 * `-i 1` leaves the written CSV's error column literally `NaN`, which
 * `ThroughputReport.parseCsv` refuses outright — so a smoke run can prove the harness
 * measures and can never become a findings row, the same asymmetry
 * `FanOutScalingBenchmark` documents.
 *
 * The JMH knobs below come from `BoundedReadFixtures`' `E1_*` constants for the reason
 * `OperatorThroughputBenchmark` gives for the same indirection: a renderer recording the
 * `RunEnvironment` of a sweep must not be able to disagree with the configuration the
 * benchmark actually ran under. They are the repository's convention — 5 forks, 5 warmup +
 * 5 measurement iterations of 1 s — raised on `computenet-x9e.6.4` from the 1 fork /
 * 3 warmup this file shipped with; the constants' own comment gives both reasons.
 *
 * **A sweep must NOT raise the sample at the command line** (`-f`, `-wi`, `-i`), and an
 * earlier revision of this paragraph wrongly recommended exactly that: the renderer builds
 * its `RunEnvironment` from the constants, so a flag-raised run publishes under a
 * configuration it did not run at — the disagreement the indirection exists to prevent.
 * Raise the constants instead.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(BoundedReadFixtures.E1_FORKS)
@Warmup(
    iterations = BoundedReadFixtures.E1_WARMUP_ITERATIONS,
    time = BoundedReadFixtures.E1_ITERATION_SECONDS,
    timeUnit = TimeUnit.SECONDS,
)
@Measurement(
    iterations = BoundedReadFixtures.E1_MEASUREMENT_ITERATIONS,
    time = BoundedReadFixtures.E1_ITERATION_SECONDS,
    timeUnit = TimeUnit.SECONDS,
)
open class BoundedReadBenchmark {

    /**
     * The unhosted subject: one `SetCell<Int>` populated to [scale] elements.
     *
     * `Level.Trial`, not `Level.Iteration`: `snapshot()` is a read, so the state it copies
     * is *identical* on every invocation of the whole trial and there is nothing to
     * rebuild between iterations. That is a real difference from
     * `OperatorThroughputBenchmark`, whose subject mutates and whose graph must therefore
     * be rebuilt per iteration — and it is why no tag-map growth caveat applies to this
     * benchmark: nothing here grows.
     *
     * `@Param` carries [scale] with no explicit value list, so JMH fills it from
     * `SetScale`'s own constants and a scale added to the fixture enters the sweep without
     * anyone editing this file.
     */
    @State(Scope.Thread)
    open class DirectState {

        /**
         * Prints this fork's host facts to stdout once per trial — the same mechanism
         * `OperatorThroughputBenchmark.GraphState.announceHost` and
         * `FanOutScalingBenchmark.RigState.announceHost` use, for the identical reason
         * (computenet-yhbd, closed here for this class by computenet-7w4e):
         * `civictech.bench.HostFacts.fromJmhLog` is the ONLY source
         * `RunEnvironment.forRun`'s JMH-sweep overload accepts, it reads these lines off
         * the tee'd run log, and it refuses — `HostFactsUnknownException` — rather than
         * falling back to the rendering process's own host. Without this hook every log
         * this class produced was refused, which is why the E1 entry in
         * `doc/bench/findings.md` was rendered through a throwaway driver.
         *
         * `Level.Trial` runs INSIDE the measuring fork, before warmup, which is what
         * makes the line trustworthy: the renderer runs in a different, later process,
         * possibly on a different machine, and no JMH artifact otherwise records which
         * host measured.
         *
         * **[HostedState] carries its own copy of this hook, deliberately.** The two
         * subjects are unrelated `@State` classes, and JMH runs `Level.Trial` setup only
         * for the states a given `@Benchmark` method actually takes — so a sweep narrowed
         * to `BoundedReadBenchmark.realHostedSnapshotOf` would produce a log with no
         * banner at all if only this class announced. The duplication is what makes
         * either method independently renderable.
         */
        @Setup(Level.Trial)
        fun announceHost() {
            // The leading newline is load-bearing — see OperatorThroughputBenchmark's
            // identical comment for the measured reason (JMH writes its progress prefix
            // with no trailing newline and relays this fork's stdout onto that line).
            println()
            HostFacts.captureCurrent().bannerLines().forEach(::println)
        }

        @Param
        @JvmField
        var scale: SetScale = SetScale.N1E3

        private lateinit var subject: DirectCopySubject

        @Setup(Level.Trial)
        fun populate() {
            subject = BoundedReadFixtures.directCopySubject(scale)
        }

        /** One whole-state copy on the calling (JMH worker) thread. */
        fun copy(): Any = subject.snapshot()
    }

    /**
     * The hosted subject: the same populated cell spawned on a real
     * `ManagedHost`/`VirtualThreadScheduler`, read through `ManagedHost.snapshotOf`.
     *
     * Real host, never `SimulationController` — the invariant the whole replication rests
     * on. Under a simulated host the copy would run on the JMH worker's own `runToIdle`
     * call and the submit/dequeue/future cost this benchmark exists to isolate would
     * vanish into the drive.
     *
     * The scheduler is shut down at `Level.Trial` teardown; without it each fork leaks one
     * drain thread per parameter combination.
     */
    @State(Scope.Thread)
    open class HostedState {

        /**
         * This fork's host facts — see [DirectState.announceHost] for the mechanism, the
         * refusal it unblocks, and why each of the two `@State` classes in this file
         * carries its own copy rather than sharing one.
         */
        @Setup(Level.Trial)
        fun announceHost() {
            println()
            HostFacts.captureCurrent().bannerLines().forEach(::println)
        }

        @Param
        @JvmField
        var scale: SetScale = SetScale.N1E3

        private lateinit var subject: HostedCopySubject

        @Setup(Level.Trial)
        fun spawn() {
            subject = BoundedReadFixtures.hostedCopySubject(scale)
        }

        @TearDown(Level.Trial)
        fun shutdown() {
            subject.close()
        }

        /** One whole-state copy end to end: submit, dequeue, copy, future completion. */
        fun copy(): Any = subject.snapshotOf()
    }

    /**
     * E1's direct column: `Stateful.snapshot()` on an unhosted `SetCell`.
     *
     * **Renamed from `direct` by computenet-7w4e** — entries already in
     * `doc/bench/findings.md` labelled `E1 direct <scale>` were measured under the old
     * name and are left standing as the history they are; this is the method they name.
     *
     * The `real` prefix is not decoration: `ThroughputReport.driveOf` tokenizes a method
     * name and requires exactly one `sim`/`real` token, so `direct` was REFUSED outright
     * (`[BEN1-26]`/`[BEN1-27]` — a result must never lose the regime that produced it) and
     * the E1 entry had to be rendered through a throwaway driver that STATED the drive
     * instead, which is the substitution those requirements exist to prevent in shipped
     * code.
     *
     * `real` is the honest answer even though this subject is UNHOSTED. There is no drive
     * here at all — no scheduler, no host queue, no `SimulationController` and no
     * `SimWorld` anywhere in `BoundedReadFixtures`, whose own header records "Real host,
     * never a simulation … There is deliberately no SIM variant". [Drive][civictech.bench.Drive]
     * distinguishes a measurement taken under simulated time from one taken on real
     * hardware, and an unhosted direct call is the latter; `CellFootprintBenchmark`'s
     * `realSnapshot` names its likewise-unhosted subject `real` for exactly this reason.
     *
     * The returned state is consumed through the blackhole so the copy cannot be
     * eliminated — a `snapshot()` whose result is dropped is exactly the shape a JIT is
     * entitled to remove, and the number that would produce is not a measurement of
     * anything.
     */
    @Benchmark
    fun realDirect(state: DirectState, blackhole: Blackhole) {
        blackhole.consume(state.copy())
    }

    /**
     * E1's end-to-end column: `ManagedHost.snapshotOf(ref).get()` on a real threaded host.
     *
     * **Renamed from `hostedSnapshotOf` by computenet-7w4e**, for the reason
     * [realDirect]'s KDoc gives — entries already in `doc/bench/findings.md` labelled
     * `E1 hostedSnapshotOf <scale>` were measured under the old name and stay as
     * history; this is the method they name. Here the `real` token is literal as well as
     * required: the subject runs on a `ManagedHost`/`VirtualThreadScheduler`.
     */
    @Benchmark
    fun realHostedSnapshotOf(state: HostedState, blackhole: Blackhole) {
        blackhole.consume(state.copy())
    }
}
