package civictech.bench.micro

import civictech.bench.Drive
import civictech.bench.HostFacts
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
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
 * Operator throughput over the BEN1 micro-graphs: every subject x every direction, under
 * both drives (`[BEN1-17]`, `[BEN1-18]`, `[BEN1-26]`, `[BEN1-27]`).
 *
 * ## What one measured operation is
 *
 * One invocation applies a pre-generated batch of [ThroughputReport.DELTAS_PER_BATCH]
 * deltas to the subject's graph **and drives that graph to quiescence** — `runToIdle`
 * under [Drive.SIM], the drain fence under [Drive.REAL]. `@OperationsPerInvocation` is
 * that batch size, so the reported `ops/s` is *deltas* per second rather than batches
 * per second.
 *
 * **The quiescence drive is part of the number, by design.** A body that applied a batch
 * and returned would measure enqueueing, not propagation: `MicroGraph.applyBatch` returns
 * as soon as the deltas are on the host queue, so the operator's own work would land in
 * whatever iteration happened to drain it — or, at the end of a run, not at all. Every
 * result this benchmark produces therefore includes the cost of settling the graph, and
 * a SIM number and a REAL number differ partly *because* their settling differs. That is
 * the point of measuring both, not a defect to subtract out.
 *
 * ## Retract mechanics, and why the reload is not in the number
 *
 * State cannot shrink forever: a retract batch has to have something to retract. The
 * mechanism here is a `Level.Invocation` setup that, for [Direction.RETRACT], applies the
 * covering insert batch and quiesces the graph **before** the timed body starts, then
 * hands the body `Deltas.retract(...)` of exactly that batch. JMH excludes `@Setup` from
 * the measured interval, so the reload cost is paid and not counted.
 *
 * Two consequences a reader of the numbers has to carry:
 *
 * - A retract invocation costs roughly twice the wall clock of an insert invocation, all
 *   of it real work, only half of it measured. A sweep's *duration* is therefore not
 *   proportional to its reported throughput.
 * - `Level.Invocation` is JMH's own documented caveat: its per-invocation timestamps are
 *   unreliable for operations shorter than roughly a millisecond.
 *   [ThroughputReport.DELTAS_PER_BATCH] is 512 partly for that reason — 512 deltas
 *   through a hosted graph plus a quiescence fence is comfortably above that floor. A
 *   future task that shrinks the batch has to re-examine this, not just the constant.
 *
 * ## Tag-map growth is observed here, never tuned
 *
 * Within one iteration the graph is reused across invocations and the delta stream never
 * repeats an element or a tag, so the operators' `TagState` maps grow monotonically —
 * live tags under insert, tombstones under retract — for the whole iteration.
 * `[BEN1-28]` names exactly that growth as the suspect for dominating the set-shaped
 * subjects' numbers, so it is left in and stated: the graph is rebuilt at
 * `Level.Iteration`, which bounds the growth per iteration and makes iteration-to-
 * iteration drift within a fork observable rather than smoothed away. Nothing here
 * clears, compacts or otherwise tunes that map, and a later result that finds throughput
 * declining across an iteration is reporting a real property of the implementation.
 *
 * ## Caveats that make naive comparisons invalid
 *
 * - **Cross-arity comparison.** `MicroGraph.applyBatch` pushes every delta into *every*
 *   source, so a two-source subject ([Subject.INTERSECT], [Subject.QUORUM],
 *   [Subject.JOIN_SET], ...) costs 2N host invocations per N-delta batch while a
 *   one-source subject costs N. Its `ops/s` is not comparable to a one-source subject's
 *   without saying so.
 * - **[Subject.TAGGED_SET] is a filter-identity baseline**, not a measurement of
 *   `TaggedSetOperator` in isolation — see that constant's own KDoc. No result derived
 *   from it may claim otherwise.
 * - **`MergeableGroupByCell` has no subject at all** — 14 of the epic's 15 cells are
 *   covered, and the fifteenth is refused at link time for a stated reason (the named
 *   omission in `Graphs.kt`). A sweep of this benchmark is not a sweep of all 15.
 *
 * ## Running it
 *
 * ```
 * ./gradlew :bench:jmhJar
 * java -jar bench/build/libs/bench-jmh.jar OperatorThroughputBenchmark \
 *      -rf csv -rff /abs/path/throughput.csv
 * ```
 *
 * A single-combination smoke run (the shape used to prove the harness measures at all,
 * roughly two minutes rather than the full sweep's half hour):
 *
 * ```
 * java -jar bench/build/libs/bench-jmh.jar 'OperatorThroughputBenchmark.sim' \
 *      -p subject=FILTER -p direction=INSERT -f 1 -wi 1 -i 1 -rf csv -rff /tmp/smoke.csv
 * ```
 *
 * [ThroughputReport] turns the resulting CSV into findings entries; see its KDoc for the
 * rendering invocation. The JMH knobs below are declared through `ThroughputReport`'s
 * constants so this class's own configuration lives in one place; they are NOT what the
 * renderer records. JMH's `-f`/`-wi`/`-i` flags override these annotations, so the
 * `RunEnvironment` a sweep produces could disagree with them, and the render path does
 * not read these constants at all — it reads what the run actually resolved off the
 * run's own log (`RunKnobs.fromJmhLog`, computenet-x9e.8). [GraphState.announceHost]
 * below is the same pattern one field over, for the CPU/core/OS facts no JMH artifact
 * states on its own (computenet-yhbd).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(ThroughputReport.DELTAS_PER_BATCH)
@Fork(ThroughputReport.FORKS)
@Warmup(
    iterations = ThroughputReport.WARMUP_ITERATIONS,
    time = ThroughputReport.ITERATION_SECONDS,
    timeUnit = TimeUnit.SECONDS,
)
@Measurement(
    iterations = ThroughputReport.MEASUREMENT_ITERATIONS,
    time = ThroughputReport.ITERATION_SECONDS,
    timeUnit = TimeUnit.SECONDS,
)
open class OperatorThroughputBenchmark {

    /**
     * The graph under test, its delta stream, and the batch the next invocation applies.
     *
     * `@Param` covers [subject] — every constant of [Subject], filled in by JMH from the
     * enum itself rather than from a hand-written list, so a subject added to the fixture
     * enters the sweep without anyone remembering to add it here — and [direction], the
     * two constants of [Direction].
     *
     * The drive is NOT a `@Param`. It is a constructor argument of the two subclasses
     * below, which exist so that SIM and REAL are separately *named* benchmarks:
     * `[BEN1-26]`/`[BEN1-27]` turn on a result never losing the regime that produced it,
     * and a benchmark name is the one label that survives into every artifact JMH emits.
     * [ThroughputReport.driveOf] reads it back off that name and refuses a name that does
     * not state exactly one drive.
     */
    @State(Scope.Thread)
    open class GraphState(private val drive: Drive) {

        @Param
        @JvmField
        var subject: Subject = Subject.TAGGED_SET

        @Param
        @JvmField
        var direction: Direction = Direction.INSERT

        private lateinit var graph: MicroGraph
        private lateinit var stream: DeltaStream

        /** The batch the next timed invocation applies; prepared by [prepareBatch]. */
        private lateinit var pending: DeltaBatch

        /**
         * Prints this fork's host facts to stdout once per trial — the CPU model, core
         * count and OS the render path has no other way to learn (`[BEN1-23]`,
         * computenet-yhbd).
         *
         * `Level.Trial` runs INSIDE the measuring fork, before its warmup and
         * measurement iterations, which is exactly what makes this line trustworthy
         * where a renderer's own `Runtime.getRuntime()` read is not: the renderer runs
         * in a different, later process, possibly on a different machine, and no JMH
         * artifact otherwise records which host measured. The line lands on the same
         * stdout that `ThroughputReport`'s command block already tees beside the results
         * file for [MeasuringJvm][civictech.bench.MeasuringJvm]'s and
         * [RunKnobs][civictech.bench.RunKnobs]'s banners, so
         * [HostFacts.fromJmhLog][civictech.bench.HostFacts.Companion.fromJmhLog] reads it
         * from the exact same artifact — no new file, no new convention.
         *
         * Fires once per fork per parameter combination, so a full sweep prints it many
         * times; every occurrence states the same host, and
         * `civictech.bench.HostFacts.Companion.fromJmhLog`'s `distinct()` collapses that
         * the same way it already collapses JMH's own repeated banner.
         */
        @Setup(Level.Trial)
        fun announceHost() {
            // The leading newline is load-bearing. JMH writes its progress prefix
            // (`# Warmup Iteration   1: `) with no trailing newline and then relays this
            // fork's stdout onto that same line, so without it the first fact printed
            // below lands mid-line rather than at column 0 — measured on a 2-fork sweep,
            // which is how the CPU model went missing from every real run log (review of
            // computenet-yhbd). `HostFacts.fromJmhLog` matches the marker mid-line too,
            // so the two halves are independently sufficient; this one is what keeps the
            // artifact readable for a human.
            println()
            HostFacts.captureCurrent().bannerLines().forEach(::println)
        }

        /**
         * Builds the graph and a fresh delta stream once per iteration.
         *
         * `Graphs.build` returns an already-quiescent graph, so no construction cost
         * leaks into the first invocation. The stream is fresh per iteration for the same
         * reason the graph is: element and tag counters restart together with the state
         * they are applied to, and a stream shared across iterations would eventually
         * exhaust nothing but would make every iteration's element range different from
         * every other's for no stated reason.
         */
        @Setup(Level.Iteration)
        fun openGraph() {
            graph = Graphs.build(subject = subject, drive = drive)
            stream = DeltaStream(SEED)
        }

        @TearDown(Level.Iteration)
        fun closeGraph() {
            graph.close()
        }

        /**
         * Generates the next batch — and, under [Direction.RETRACT], pre-loads the state
         * it retracts — outside the measured interval.
         *
         * Generation cannot happen inside the body: `DeltaStream` allocates and shuffles,
         * and a body that re-applied one cached batch instead would be measuring the
         * dedup-absorb path (`Deltas.kt`'s "tag churn" note), not the operator's work.
         */
        @Setup(Level.Invocation)
        fun prepareBatch() {
            val inserts = stream.insert(ThroughputReport.DELTAS_PER_BATCH)
            pending = when (direction) {
                Direction.INSERT -> inserts
                Direction.RETRACT -> {
                    graph.applyAndQuiesce(inserts)
                    Deltas.retract(inserts)
                }
            }
        }

        /**
         * The measured work: apply the prepared batch, drive to quiescence, and return
         * the collector's arrival count so the whole propagation is observably consumed
         * rather than eligible for elimination.
         */
        fun applyAndQuiesce(): Long {
            graph.applyAndQuiesce(pending)
            return graph.arrivals
        }
    }

    /** [Drive.SIM] state — deterministic single-threaded simulation, drained by `runToIdle`. */
    @State(Scope.Thread)
    open class SimState : GraphState(Drive.SIM)

    /** [Drive.REAL] state — a `ManagedHost` on virtual threads, drained by the fence. */
    @State(Scope.Thread)
    open class RealState : GraphState(Drive.REAL)

    @Benchmark
    fun sim(state: SimState, blackhole: Blackhole) {
        blackhole.consume(state.applyAndQuiesce())
    }

    @Benchmark
    fun real(state: RealState, blackhole: Blackhole) {
        blackhole.consume(state.applyAndQuiesce())
    }

    companion object {

        /**
         * The delta stream's seed.
         *
         * Fixed rather than randomized: `DeltaStream`'s seed chooses arrival order and
         * the tag source id, so a fixed seed makes two runs' inputs byte-identical and a
         * measured difference between them cannot be an input difference.
         */
        const val SEED: Long = 20260818L
    }
}
