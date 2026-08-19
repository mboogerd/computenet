package civictech.bench.micro

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
 * java -jar bench/build/libs/bench-jmh.jar CellFootprintBenchmark -prof gc \
 *      -rf csv -rff /abs/path/footprint-alloc.csv 2>&1 | tee /abs/path/footprint-alloc.log
 * ```
 *
 * A single-combination smoke run:
 *
 * ```
 * java -jar bench/build/libs/bench-jmh.jar CellFootprintBenchmark -prof gc \
 *      -p family=SET_CELL -p scale=N1E3 -f 1 -wi 1 -i 1
 * ```
 *
 * The `| tee` matters for the same reason it does for `OperatorThroughputBenchmark`: JMH's
 * results file records nothing about the JVM that produced it, and `MeasuringJvm.fromJmhLog`
 * reads the banner beside it. **`ThroughputReport` cannot render this file** — it parses the
 * primary `Score` column, and this benchmark's answer lives in a `-prof gc` secondary
 * metric. Reading these numbers is a hand step, deliberately; wiring a renderer for a
 * secondary metric belongs to whichever task actually needs one.
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
