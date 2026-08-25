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
 * ## Invocation mechanics, and why the state-restoring work is not in the number
 *
 * Neither direction can run open-loop: a retract batch has to have something to retract,
 * and an insert batch applied to a graph nothing ever drains piles up. Both are handled
 * by the same `Level.Invocation` setup, which delegates to [InvocationCycle] — the graph
 * is returned to its per-invocation baseline live state **before** the timed body starts,
 * by applying the covering insert batch under [Direction.RETRACT] and by retracting the
 * previous body's batch under [Direction.INSERT]. JMH excludes `@Setup` from the measured
 * interval, so that cost is paid and not counted.
 *
 * Two consequences a reader of the numbers has to carry:
 *
 * - Every invocation, in either direction, costs roughly twice the wall clock its
 *   reported throughput accounts for: all of it real work, only half of it measured. A
 *   sweep's *duration* is therefore not proportional to its reported throughput. (Before
 *   computenet-y7hc this was true of retract invocations only, which is why the two
 *   directions' sweep durations were as asymmetric as their numbers.)
 * - `Level.Invocation` is JMH's own documented caveat: its per-invocation timestamps are
 *   unreliable for operations shorter than roughly a millisecond.
 *   [ThroughputReport.DELTAS_PER_BATCH] is 512 partly for that reason — 512 deltas
 *   through a hosted graph plus a quiescence fence is comfortably above that floor. A
 *   future task that shrinks the batch has to re-examine this, not just the constant.
 *
 * ## Live state is bounded per invocation; tombstone growth is observed, never tuned
 *
 * Within one iteration the graph is reused across invocations and the delta stream never
 * repeats an element or a tag, so the operators' `TagState` maps accumulate for the whole
 * iteration. Two halves of that accumulation, which this class now treats differently —
 * a change of decision recorded here rather than quietly made (computenet-y7hc):
 *
 * - **Live membership is bounded per invocation.** It was not. Under
 *   [Direction.RETRACT] the covering-insert-then-retract shape always returned the graph
 *   to its pre-invocation live state, but under [Direction.INSERT] the setup only
 *   generated a batch, so live tags grew monotonically and unboundedly across an
 *   iteration: invocation 1's timed body ran against an empty map and invocation 40's
 *   against 40 batches of live membership. That is not the operator property `[BEN1-28]`
 *   asks about — it is a *harness* confound that makes the two directions incomparable.
 *   Two 2026-08-18 findings entries attribute their INSERT/RETRACT dispersion split to
 *   exactly this mechanism, by name — the SIM entry (`computenet-x9e.4.4`: 5 of 5
 *   Reportable rows are RETRACT rows) and the REAL entry (`computenet-x9e.4.5`: every
 *   INSERT row at least 3x noisier than every RETRACT row's upper bound). **Neither is
 *   evidence that the fix below closes anything**, and the second is a superseded entry:
 *   the JDK-21 re-measurement that supersedes it (`computenet-am2h`) reports INSERT
 *   0.00813–0.07387 against RETRACT 0.00771–0.05361 — overlapping ranges with no 3x
 *   separation — and explicitly declines to re-litigate `[BEN1-28]`. Which of those
 *   pictures survives a re-measured sweep is computenet-x9e.14's to settle, not this
 *   change's. INSERT now carries the mirror
 *   of RETRACT's covering insert, an untimed compensating retract of the previous body's
 *   batch, so both directions time one batch against the same live state every
 *   invocation. [InvocationCycle] holds that logic and
 *   `BoundedInvocationStateTest` pins the bound, so a change that reintroduces the growth
 *   reddens `:bench:test` instead of silently restoring the old shape.
 * - **Tombstone growth is still observed and never tuned.** The observed-remove algebra
 *   keeps a tombstone per retracted tag, so total map size still grows monotonically
 *   within an iteration — now in both directions, where before it was live tags under
 *   insert and tombstones under retract. `[BEN1-28]` names that growth as the suspect for
 *   dominating the set-shaped subjects' numbers, so it is left in and stated: the graph is
 *   rebuilt at `Level.Iteration`, which bounds it per iteration and makes iteration-to-
 *   iteration drift within a fork observable rather than smoothed away. Nothing here
 *   clears, compacts or otherwise tunes that map, and a later result that finds throughput
 *   declining across an iteration is reporting a real property of the implementation.
 *
 * What the change does **not** establish: that `[BEN1-28]`'s dispersion asymmetry is
 * resolved. That is an empirical claim, it needs a re-measured sweep on a quiesced host,
 * and it belongs to computenet-x9e.14. Until that lands, the numbers in the findings
 * corpus are the pre-change ones and nothing here supersedes them.
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

        /**
         * The untimed/timed split, and the bounded-live-state invariant that goes with
         * it. Lives in `src/main` so `:bench:test` can pin it — see [InvocationCycle]'s
         * own KDoc for why that is not a stylistic choice.
         */
        private lateinit var cycle: InvocationCycle

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
            cycle = InvocationCycle(graph, DeltaStream(SEED), direction)
        }

        @TearDown(Level.Iteration)
        fun closeGraph() {
            graph.close()
        }

        /**
         * The untimed half of one invocation, delegated to [InvocationCycle.prepare]:
         * generate the next batch, and restore the graph's per-invocation baseline live
         * state — a covering insert under [Direction.RETRACT], a compensating retract of
         * the previous body's batch under [Direction.INSERT].
         *
         * Neither half can move into the body. Generation allocates and shuffles, and a
         * body that re-applied one cached batch instead would be measuring the
         * dedup-absorb path (`Deltas.kt`'s "tag churn" note), not the operator's work;
         * the state-restoring apply is a whole extra batch of real work, and counting it
         * would put two batches inside a number reported as one batch's throughput. JMH
         * excludes `@Setup` from the measured interval, so both are paid and not counted.
         */
        @Setup(Level.Invocation)
        fun prepareBatch() = cycle.prepare()

        /**
         * The measured work: apply the prepared batch, drive to quiescence, and return
         * the collector's arrival count so the whole propagation is observably consumed
         * rather than eligible for elimination.
         */
        fun applyAndQuiesce(): Long = cycle.applyPending()
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
