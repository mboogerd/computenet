package civictech.bench.micro

import civictech.bench.Drive
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
 * BS-8's fan-out curve: per-delta cost as a function of outlet fan-out degree over
 * `civictech.cell.port.FanOutlet`'s own consumer-iterating `fanOut` loop, across
 * [FanDegree]'s five degrees — 1 to 256, four degrees over more than two orders of
 * magnitude (`[BEN1-19]`, BS-8).
 *
 * ## What one measured operation is
 *
 * One invocation applies exactly ONE fresh delta to [FanOutFixtures.rig]'s source and
 * drives the host to quiescence — `runToIdle` under [Drive.SIM], the `awaitDrained` fence
 * under [Drive.REAL] — then returns the summed arrival count across every attached
 * collector so the whole fan-out is observably consumed rather than eligible for
 * elimination. `Mode.AverageTime` with no `@OperationsPerInvocation` override (default 1)
 * is deliberate: the reported score is per-delta latency at the parameterized [degree],
 * not a throughput figure, which is what [FanOutFixtures]'s design calls for and what
 * distinguishes this benchmark from `OperatorThroughputBenchmark`'s `Mode.Throughput`
 * batches.
 *
 * **The quiescence drive is part of the number**, for the same reason
 * `OperatorThroughputBenchmark`'s KDoc gives: `FanOutRig.applyOneAndQuiesce` returns only
 * once every collector has actually been reached, so a result here is the cost of one
 * delta actually fanning out to `degree.subscribers` consumers — not merely the cost of
 * enqueueing it.
 *
 * ## The rig is rebuilt per ITERATION, not per invocation
 *
 * Exactly as `OperatorThroughputBenchmark.GraphState.openGraph` rebuilds its graph once
 * per `Level.Iteration`: [FanOutFixtures.rig]'s source is a `SetCell<Int>`, and every
 * invocation within one iteration mints one more element into it, so the source's own tag
 * maps grow monotonically across an iteration's invocations. That growth is left in and
 * observed, never tuned away — a future reader who finds throughput drifting across an
 * iteration is reading a real property of the fixture, not a benchmark defect. The rig is
 * rebuilt fresh at the START of each iteration, which is what bounds that growth to one
 * iteration's worth rather than letting it accumulate across the whole fork.
 *
 * ## What this benchmark is not
 *
 * It measures ONE outlet's fan-out cost at a fixed degree, never the late-join `CatchUp`
 * path a new subscriber takes against an already-populated operator — that measurement
 * belongs to the sibling task computenet-x9e.5.2, over a different rig entirely
 * (`CatchUpFixtures.kt`). A result from this benchmark says nothing about catch-up cost.
 *
 * ## Running it
 *
 * ```
 * ./gradlew :bench:jmhJar
 * ~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java \
 *      -jar bench/build/libs/bench-jmh.jar FanOutScalingBenchmark \
 *      -rf csv -rff /abs/path/fanout.csv 2>&1 | tee /abs/path/fanout.log
 * ```
 *
 * **Invoke the Gradle toolchain's own JDK 21 by path, never a bare `java`.** `NOISE_FLOOR`
 * (`civictech.bench.Dispersion`) was derived on Temurin 21 and every other entry in
 * `doc/bench/findings.md` is measured there, so a sweep on another JVM is not comparable
 * to the threshold it will be classified against. A bare `java` on the machine this file
 * was written on resolves to Homebrew JDK 26 — the exact substitution that produced the
 * superseded REAL-drive throughput entry (`computenet-hqid`), five major JDK versions off
 * the claimed environment. Nothing in this build pins it for you: the JMH jar is an
 * ordinary executable jar and Gradle is not in the loop once it is built. The run's own
 * `# VM version:` banner line, retained in the `.log` beside the results, is the check
 * that the right JVM took — read it, do not assume it.
 *
 * The `tee` is not optional if the results are going to become a findings entry: JMH's CSV
 * carries no JVM columns, so `civictech.bench.MeasuringJvm.fromJmhLog` reads the measuring
 * JVM off the banner in that log and refuses without it, and
 * `civictech.bench.HostFacts.fromJmhLog` reads [GraphState.announceHost]'s CPU/core/OS
 * banner off the very same log (computenet-yhbd).
 *
 * A single-combination smoke run, to prove the harness measures at all — **on the same
 * pinned JDK, for the same reason**; a bare `java` here measured 11.127 us/op at REAL D1 on
 * Homebrew JDK 26.0.1 (checked 2026-08-19), which is a number from the wrong runtime even
 * though nothing in the output complains:
 *
 * ```
 * ~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java \
 *      -jar bench/build/libs/bench-jmh.jar 'FanOutScalingBenchmark.real' \
 *      -p degree=D1 -f 1 -wi 1 -i 1 -rf csv -rff /tmp/fanout-smoke.csv
 * ```
 *
 * `-i 1` leaves the CSV's error column literally `NaN` (JMH writes no dispersion at or below
 * two measurement samples), and `ThroughputReport.parseCsv` **refuses** such a row outright —
 * "the run produced too few samples for a dispersion … Re-run with at least three measurement
 * iterations". So a smoke run can prove the harness measures and can never become a findings
 * row, which is the intended asymmetry rather than a limitation to work around.
 *
 * The JMH knobs below come from [FanOutFixtures]'s constants for the reason
 * `OperatorThroughputBenchmark` and `BoundedReadBenchmark` give for the same indirection:
 * a renderer recording the `RunEnvironment` of a sweep must not be able to disagree with
 * the configuration the benchmark actually ran under. **A sweep must NOT raise the sample
 * at the command line** (`-f`, `-wi`, `-i`) — raise the constants in `FanOutFixtures`
 * instead, or the renderer publishes a `RunEnvironment` the run did not actually measure
 * under.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(FanOutFixtures.FORKS)
@Warmup(
    iterations = FanOutFixtures.WARMUP_ITERATIONS,
    time = FanOutFixtures.ITERATION_SECONDS,
    timeUnit = TimeUnit.SECONDS,
)
@Measurement(
    iterations = FanOutFixtures.MEASUREMENT_ITERATIONS,
    time = FanOutFixtures.ITERATION_SECONDS,
    timeUnit = TimeUnit.SECONDS,
)
open class FanOutScalingBenchmark {

    /**
     * The rig under test and the degree JMH parameterizes it at.
     *
     * The drive is NOT a `@Param`, for the reason `OperatorThroughputBenchmark.GraphState`
     * gives for the identical split: it is a constructor argument of the two subclasses
     * below, so SIM and REAL are separately NAMED benchmarks and a result can never lose
     * the regime that produced it (`[BEN1-26]`/`[BEN1-27]`).
     */
    @State(Scope.Thread)
    open class RigState(private val drive: Drive) {

        /**
         * `@Param` with no explicit value list, so JMH fills it from [FanDegree]'s own
         * constants — a degree added to the fixture enters the sweep without anyone
         * editing this class.
         */
        @Param
        @JvmField
        var degree: FanDegree = FanDegree.D1

        private lateinit var rig: FanOutRig

        /**
         * Prints this fork's host facts to stdout once per trial — the same mechanism
         * `OperatorThroughputBenchmark.GraphState.announceHost` uses and for the identical
         * reason (computenet-yhbd): `HostFacts.fromJmhLog` needs this line in the tee'd
         * log, and `Level.Trial` runs inside the measuring fork, before warmup, which is
         * what makes it trustworthy.
         */
        @Setup(Level.Trial)
        fun announceHost() {
            // Leading newline load-bearing — see OperatorThroughputBenchmark's identical
            // comment for the measured reason (JMH's progress prefix has no trailing
            // newline).
            println()
            HostFacts.captureCurrent().bannerLines().forEach(::println)
        }

        /**
         * Builds a fresh rig once per iteration — see this class's KDoc for why per
         * iteration, not per invocation.
         */
        @Setup(Level.Iteration)
        fun openRig() {
            rig = FanOutFixtures.rig(degree, drive)
        }

        @TearDown(Level.Iteration)
        fun closeRig() {
            rig.close()
        }

        /**
         * The measured work: one fresh delta, driven to quiescence, returning the summed
         * arrival count across every collector so the fan-out cannot be eliminated.
         */
        fun applyOneAndQuiesce(): Long = rig.applyOneAndQuiesce()
    }

    /** [Drive.SIM] state — deterministic single-threaded simulation, drained by `runToIdle`. */
    @State(Scope.Thread)
    open class SimState : RigState(Drive.SIM)

    /** [Drive.REAL] state — a `ManagedHost` on virtual threads, drained by the fence. */
    @State(Scope.Thread)
    open class RealState : RigState(Drive.REAL)

    @Benchmark
    fun sim(state: SimState, blackhole: Blackhole) {
        blackhole.consume(state.applyOneAndQuiesce())
    }

    @Benchmark
    fun real(state: RealState, blackhole: Blackhole) {
        blackhole.consume(state.applyOneAndQuiesce())
    }
}
