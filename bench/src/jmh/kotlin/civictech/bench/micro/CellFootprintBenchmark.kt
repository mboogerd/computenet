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
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.io.Serializable
import java.util.concurrent.TimeUnit

/**
 * The **allocation** cost of one `Stateful.snapshot()` per data-cell family and scale
 * (computenet-x9e.6.1; the epic's `-prof gc` technique, section 3.3).
 *
 * ## Read this first: this is NOT the `[BEN1-20]` footprint instrument
 *
 * `[BEN1-20]` asks what a cell's state COSTS TO HOLD — a retained-size question — and that
 * instrument is `Footprint.kt` in `bench/src/main/kotlin`, which answers it by differential
 * live-heap accounting. This class measures a different quantity that happens to be
 * adjacent: **bytes allocated per snapshot call**, which JMH reports as
 * `gc.alloc.rate.norm` under `-prof gc`. Allocation is not retention — a snapshot allocates
 * its copy plus every intermediate the copy passes through, and a populate-then-hold
 * workload allocates far more than it retains — so no number from this class may be
 * presented as a footprint, and no footprint entry may cite it as one.
 *
 * It is here for two reasons, both concrete:
 *
 * 1. **It is the one V1C-BENCH column this tree could not otherwise re-derive.** E1's
 *    `alloc/call` figures (270 KB at 1e3, 2.61 MB at 1e4, 26.9 MB at 1e5 for `SetCell`,
 *    `doc/spec/90-roadmap/98-inspector-v4-plan/30-bounded-read-measurement.md` section 3)
 *    are allocation per `snapshot()` call, measured with
 *    `ThreadMXBean.getThreadAllocatedBytes`. `-prof gc` measures the same quantity through
 *    a different mechanism, which makes this a genuine cross-check on that document's
 *    numbers rather than a re-run of its harness.
 *
 *    **And it reproduces.** Measured 2026-08-19 on an Apple M2 Pro (10 cores, JDK 21.0.11
 *    Temurin, `-Xmx2g`), one combination at reduced iteration counts
 *    (`-p family=SET_CELL -p scale=N1E3 -f 1 -wi 1 -i 2 -r 1s -w 1s`):
 *    `gc.alloc.rate.norm = 265_248 B/op` against E1's 270 KB — inside 2%, on a different
 *    machine, four months later, through a different measurement mechanism. That is a
 *    single combination at low iteration counts and therefore evidence the harness
 *    measures the right quantity, NOT the replication itself; E1's full three-scale
 *    reproduction with dispersion belongs to the V1C-BENCH replication task.
 * 2. **It bounds the copy cost the retained figure cannot.** A family whose retained state
 *    is modest but whose snapshot allocates ten times that is a different kind of problem
 *    from one that simply holds a lot, and G-21 phase 3's allocation-pressure trigger is
 *    about pressure, not occupancy.
 *
 * ## Running it
 *
 * `-prof gc` is REQUIRED. Without it the reported score is the snapshot's average time and
 * the allocation columns are simply absent — a run that looks successful and answers the
 * wrong question:
 *
 * ```
 * ./gradlew :bench:jmhJar
 * ~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java \
 *      -jar bench/build/libs/bench-jmh.jar CellFootprintBenchmark -prof gc \
 *      -rf csv -rff /abs/path/footprint-alloc.csv 2>&1 | tee /abs/path/footprint-alloc.log
 * ```
 *
 * **Invoke the Gradle toolchain's own JDK 21 by path, never a bare `java`.** `NOISE_FLOOR`
 * (`civictech.bench.Dispersion`) was derived on Temurin 21 and every other entry in
 * `doc/bench/findings.md` is measured there, so a run on another JVM produces
 * `gc.alloc.rate.norm` figures not comparable to NOISE_FLOOR or to any entry in that file. A
 * bare `java` on the machine this file was corrected on resolves to Homebrew JDK 26 — the
 * exact substitution that produced the superseded REAL-drive throughput entry
 * (`computenet-hqid`), five major JDK versions off the claimed environment. Nothing in this
 * build pins it for you: the JMH jar is an ordinary executable jar and Gradle is not in the
 * loop once it is built. The run's own `# VM version:` banner line, retained in the `.log`
 * beside the results, is the check that the right JVM took — read it, do not assume it.
 *
 * A single-combination smoke run, **on the same pinned JDK, for the same reason**:
 *
 * ```
 * ~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java \
 *      -jar bench/build/libs/bench-jmh.jar CellFootprintBenchmark -prof gc \
 *      -p family=SET_CELL -p scale=N1E3 -f 1 -wi 1 -i 1
 * ```
 *
 * `FanOutScalingBenchmark`'s smoke run warns that `-i 1` leaves a written CSV's error column
 * `NaN`, which `ThroughputReport.parseCsv` then refuses outright. That specific hazard does
 * not apply to the smoke run above: it passes no `-rf csv`, so there is no CSV for
 * `parseCsv` to refuse, and — as stated below — `ThroughputReport` cannot render this
 * benchmark's `-prof gc` secondary metric regardless of iteration count. `-i 1` here only
 * means the single reported `gc.alloc.rate.norm` value is read by hand off stdout, same as
 * any other run of this class.
 *
 * The `| tee` matters for the same reason it does for `OperatorThroughputBenchmark`: JMH's
 * results file records nothing about the JVM that produced it, and `MeasuringJvm.fromJmhLog`
 * reads the banner beside it — as does `civictech.bench.HostFacts.fromJmhLog`, off
 * [CellState.announceHost]'s CPU/core/OS lines on that same log (computenet-yhbd, wired
 * into this class by computenet-7w4e).
 *
 * **`ThroughputReport` still cannot render this class's `-prof gc` answer** — it parses the
 * primary `Score` column, and `gc.alloc.rate.norm` is a secondary metric. Reading the
 * allocation numbers is a hand step, deliberately; wiring a renderer for a secondary metric
 * is `computenet-6zqz`, not this class's business. The banner is not a workaround for that:
 * it closes a *different* refusal (host facts unknown) that also blocked the plain,
 * no-`-prof gc` time-per-`snapshot()` sweep of this same class, whose rows
 * `ThroughputReport.RowLabel.REGISTERED` already labels by `family`/`scale`.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(CellFootprintBenchmark.FORKS)
@Warmup(
    iterations = CellFootprintBenchmark.WARMUP_ITERATIONS,
    time = CellFootprintBenchmark.ITERATION_SECONDS,
    timeUnit = TimeUnit.SECONDS,
)
@Measurement(
    iterations = CellFootprintBenchmark.MEASUREMENT_ITERATIONS,
    time = CellFootprintBenchmark.ITERATION_SECONDS,
    timeUnit = TimeUnit.SECONDS,
)
open class CellFootprintBenchmark {

    /**
     * The populated cell whose `snapshot()` the body calls, built once per iteration.
     *
     * Both `@Param`s are ENUMS declared beside the catalog in `Footprint.kt`, and JMH fills
     * an enum `@Param` from the enum's own constants. So this benchmark's coverage is
     * derived from `Footprint.FAMILIES`/`Footprint.SCALES` rather than restated: a family
     * or a scale added there enters this sweep without anyone remembering to edit an
     * annotation, and one removed here fails to compile. A hand-written
     * `@Param("SetCell", "MapCell", ...)` list would be a second definition of the catalog
     * that nothing keeps in step — the same reasoning `OperatorThroughputBenchmark` applies
     * to `Subject`.
     *
     * `Level.Iteration`, not `Level.Invocation`: populating to 1e5 costs far more than one
     * snapshot, and JMH excludes setup from the measured interval either way — but a
     * per-invocation rebuild would make an iteration's wall clock almost entirely setup.
     * The cell is therefore snapshotted repeatedly, which is exactly what E1 did (20 warmup
     * plus 30 timed `snapshot()` calls against one populated cell).
     */
    @State(Scope.Thread)
    open class CellState {

        /**
         * Prints this fork's host facts to stdout once per trial — the same mechanism
         * `OperatorThroughputBenchmark.GraphState.announceHost` and
         * `FanOutScalingBenchmark.RigState.announceHost` use, for the identical reason
         * (computenet-yhbd, closed here for this class by computenet-7w4e):
         * `civictech.bench.HostFacts.fromJmhLog` is the ONLY source
         * `RunEnvironment.forRun`'s JMH-sweep overload accepts, it reads these lines off
         * the tee'd run log, and it refuses — `HostFactsUnknownException` — rather than
         * falling back to the rendering process's own host. Without this hook every log
         * this class produces was refused, however well-labelled its rows.
         *
         * `Level.Trial` runs INSIDE the measuring fork, before warmup, which is what
         * makes the line trustworthy: the renderer runs in a different, later process,
         * possibly on a different machine, and no JMH artifact otherwise records which
         * host measured.
         *
         * **This does not make a `-prof gc` run of this class renderable**, and must not
         * be read as claiming it does. `gc.alloc.rate.norm` is a JMH *secondary* metric
         * and `ThroughputReport` parses the primary `Score` column, so the allocation
         * numbers this class exists for are still a hand read off stdout — a renderer gap
         * tracked as `computenet-6zqz`, not closed here. What the banner closes is the
         * host-facts refusal, which also blocked the plain (no `-prof gc`) time-per-
         * `snapshot()` sweep of this same class, whose rows `RowLabel.REGISTERED` already
         * labels by `family`/`scale`.
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
        var family: CellFamily = CellFamily.SET_CELL

        @Param
        @JvmField
        var scale: Scale = Scale.N1E3

        private lateinit var cell: civictech.cell.Stateful

        /**
         * Populates a fresh, unhosted cell of [family] to [scale]'s element count.
         *
         * Unhosted for the same reason `Footprint` is: no scheduler, no host queue, nothing
         * between the fixture and the fold — and no `SimWorld`, so this is a REAL-drive
         * measurement in the sense `[BEN1-26]` means.
         */
        @Setup(Level.Iteration)
        fun populate() {
            cell = Footprint.of(family).populate(scale.elements)
        }

        /** One `snapshot()` call — the measured work. */
        fun snapshot(): Serializable = cell.snapshot()
    }

    /**
     * Named `real` so the drive is legible in every artifact JMH emits, the same convention
     * `OperatorThroughputBenchmark`'s `sim`/`real` split establishes and
     * `ThroughputReport.driveOf` reads back off a method name.
     */
    @Benchmark
    fun realSnapshot(state: CellState, blackhole: Blackhole) {
        blackhole.consume(state.snapshot())
    }

    companion object {

        /** `@Fork` count. One: `-prof gc`'s per-op allocation is highly reproducible. */
        const val FORKS: Int = 1

        /** `@Warmup` iteration count. */
        const val WARMUP_ITERATIONS: Int = 3

        /** `@Measurement` iteration count. */
        const val MEASUREMENT_ITERATIONS: Int = 5

        /** `@Warmup`/`@Measurement` per-iteration time, in seconds. */
        const val ITERATION_SECONDS: Int = 1
    }
}
