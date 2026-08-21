package civictech.bench.micro

import civictech.bench.Drive
import civictech.cell.CellRef
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.graph.graph
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.host.lookup
import civictech.testkit.SimWorld
import civictech.testkit.awaitDrained

// =======================================================================================
// BS-8's fan-out curve: per-delta cost as a function of outlet fan-out degree, over
// FanOutlet's own consumer-iterating path [BEN1-19].
//
// ## The subject, named precisely
//
// `kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt`'s private `fanOut(method,
// args, ctx, gate)` — taps first, then `for (key in consumerOrder) consumers[key]?.let {
// … invoke(it, …) }` — is the loop this file's rig drives at increasing width. Every
// other cost a delta pays (host dispatch, `SetCell`'s tag-map merge) is held fixed across
// [FanDegree]; only the number of attached consumers on the ONE outlet under test varies.
//
// ## Why the graph is one `SetCell<Int>` source fanning straight to N collectors
//
// `Graphs.kt`'s subjects route a delta through an *operator* before it reaches a
// collector, which is the right shape for measuring an operator's own work but the wrong
// one here: an operator in the middle would make the fan-out only one of several costs a
// wider degree changes, and any growth this rig observed could not be pinned on
// `fanOut` specifically. Wiring every collector directly to the source's own `outlet` —
// through the graph DSL's `link`, never a direct method call, so the host/link/dispatch
// layer is exercised exactly as `Graphs.kt`'s own header explains — isolates the fan-out
// width as the one thing that changes between [FanDegree] constants.
//
// `SetCell<Int>` is the source rather than `Graphs.kt`'s bare `DeltaSourceCell` because
// `SetCell.inlet.call.add` is the same element-minting API [BoundedReadFixtures] already
// uses, so this file needs no generator of its own — one fresh element per delta, through
// `Deltas.kt`'s discipline (a repeat would be absorbed by the effective-only rule and
// would measure the merge/absorb path, not fan-out).
//
// [ArrivalCollectorCell] is reused verbatim from `BoundedReadFixtures.kt` (same package,
// not edited) rather than redeclared: it already is exactly "a bystander that counts and
// timestamps arrivals on one inlet", which is all a fan-out collector needs to be.
//
// ## Wiring reused, not redeclared
//
// `Graphs.kt` already declares `enum class Wiring { LINKED, UNLINKED }` in this package
// for precisely "does this rig's builder actually connect the graph it constructs" —
// the same negative-control shape `BoundedReadFixtures.kt`'s own `RigWiring` documents
// for its rig. A third near-identical two-constant enum here would say nothing a reader
// cannot already get from either precedent, so this file reuses `Graphs.kt`'s [Wiring]
// rather than declaring `FanWiring`/`RigWiring` again (AGENTS.md: search before adding a
// new abstraction).
//
// ## Drives, reused from `civictech.bench.Drive`
//
// SIM ([SimWorld]/`runToIdle`) and REAL ([ManagedHost]/[VirtualThreadScheduler], drained
// by testkit's `awaitDrained` fence) exist side by side for the same reason `Graphs.kt`
// keeps both: SIM isolates the fan-out loop's own cost from thread hand-off, REAL is what
// a deployed host actually pays. `awaitDrained` alone is the fence for REAL, not a
// separate arrivals-count poll: `FanOutlet.fanOut`'s consumer loop invokes every consumer
// SYNCHRONOUSLY inside the one host dispatch step that processes the source's `add` (no
// consumer is queued as a separate task), so the task that mutates every collector has
// already run by the time the host's queue reports empty — the same reasoning
// `Graphs.kt`'s header gives for why `MicroGraph.applyAndQuiesce` needs no other fence.
// =======================================================================================

/**
 * Fan-out degrees the curve sweeps — the epic's own example set: four degrees spanning
 * more than two orders of magnitude (1 to 256 is 2.4 decades) [BEN1-19] (BS-8).
 *
 * An enum, not a hand-written `@Param` list, for the reason [SetScale] and `Subject`
 * already give: JMH fills [FanOutScalingBenchmark]'s `@Param` straight from these
 * constants, so a degree added here enters the sweep without anyone touching the
 * benchmark, and the fixtures' correctness tests cannot silently sweep a different set
 * than the benchmark does.
 */
enum class FanDegree(val subscribers: Int) {
    D1(1),
    D4(4),
    D16(16),
    D64(64),
    D256(256),
}

/**
 * One built, hosted fan-out rig: a single `SetCell<Int>` source linked — through the
 * graph DSL, never a direct call — to [degree]'s subscriber count worth of
 * [ArrivalCollectorCell]s, all attached to the SAME outlet.
 *
 * Lifecycle: [FanOutFixtures.rig] → repeated [applyOneAndQuiesce] → read [totalArrivals]
 * / [collectors] → [close]. Single-threaded from the caller's side, like
 * [MicroGraph][civictech.bench.micro.MicroGraph]: under [Drive.SIM] this is mandatory
 * (the simulation controller's determinism is its single-threadedness).
 */
class FanOutRig internal constructor(
    val degree: FanDegree,
    val drive: Drive,

    /**
     * Every collector this rig spawned, in link order. Public — and not just an
     * aggregate — because the fan-out correctness property is "EACH ONE of these
     * observed EVERY delta exactly once", which a summed total cannot distinguish from
     * "one collector silently ate everything a wide fan-out delivered".
     */
    val collectors: List<ArrivalCollectorCell>,
    private val sourceApi: SetApi<Int>,
    private val drain: () -> Unit,
    private val stop: () -> Unit,
) : AutoCloseable {

    /**
     * Element values are drawn from one strictly increasing counter, so no add is ever a
     * re-delivery of a tag the source already holds — the same discipline
     * [Deltas][civictech.bench.micro.Deltas] states for the throughput generators and
     * [LiveTrafficRig][civictech.bench.micro.LiveTrafficRig] states for the live-traffic
     * rig: a repeat would be absorbed by the effective-only rule and would measure the
     * absorb path instead of the fan-out path.
     */
    private var nextElement = 0

    /**
     * Distinct elements pushed through the source since the rig was built — and
     * therefore the source's exact live membership, since nothing here is ever removed.
     *
     * **Grows across invocations within one JMH iteration**, exactly as
     * [LiveTrafficRig.elementsAdded][civictech.bench.micro.LiveTrafficRig.elementsAdded]
     * documents for the live-traffic rig and `OperatorThroughputBenchmark`'s KDoc
     * documents for its graph's tag maps: [FanOutScalingBenchmark] rebuilds the rig once
     * per iteration, not per invocation, so a scale label on a rendered result names the
     * iteration's PRE-SEED size, not a size held constant through it.
     */
    val elementsAdded: Int get() = nextElement

    /** Arrivals summed across every collector — `degree.subscribers * elementsAdded` once fanned out cleanly. */
    val totalArrivals: Long get() = collectors.sumOf { it.total.get() }

    /**
     * The measured work: apply one fresh delta and drive the host to quiescence,
     * returning [totalArrivals] so the whole fan-out is observably consumed rather than
     * eligible for JIT elimination — the same shape
     * `OperatorThroughputBenchmark.GraphState.applyAndQuiesce` returns `graph.arrivals`
     * for.
     */
    fun applyOneAndQuiesce(): Long {
        sourceApi.inlet.call.add(nextElement++)
        drain()
        return totalArrivals
    }

    /**
     * Pre-seed [count] elements off any timer, in one batch drained ONCE at the end
     * rather than once per add — computenet-252t's fixed-state variant calls this from a
     * `@Setup` hook, which JMH excludes from the measured region regardless of cost, so
     * the batching only shortens wall-clock setup time and changes nothing the benchmark
     * observes.
     *
     * Exists so a caller can bring a freshly built rig to a known element count BEFORE
     * the first [applyOneAndQuiesce] it intends to measure — the mechanism
     * computenet-252t's fixed-state sweep uses to hold the source's size constant across
     * [FanDegree], rather than letting it drift with however many invocations one JMH
     * iteration happens to fit (the confound `FanOutFixtures`' header and
     * `doc/bench/findings.md`'s 2026-08-19 fan-out entry both name).
     */
    fun seed(count: Int) {
        require(count > 0) { "seed count must be positive, was $count" }
        val api = sourceApi.inlet.call
        repeat(count) { api.add(nextElement++) }
        drain()
    }

    /** Stops the [Drive.REAL] scheduler thread; a no-op under [Drive.SIM]. */
    override fun close() = stop()
}

/** The fixtures [FanOutScalingBenchmark] and `FanOutFixturesTest` are built from [BEN1-19]. */
object FanOutFixtures {

    /**
     * Hang backstop for the [Drive.REAL] drain fence, in milliseconds. Not a convergence
     * budget — crossing it means the host never drained, and the fixture fails saying so,
     * the same discipline `awaitDrained`'s own KDoc sets out and
     * [BoundedReadFixtures.DRAIN_TIMEOUT_MS][civictech.bench.micro.BoundedReadFixtures.DRAIN_TIMEOUT_MS]
     * already states for the sibling rig.
     */
    const val DRAIN_TIMEOUT_MS: Long = 120_000

    // JMH knobs, declared here rather than as literals in FanOutScalingBenchmark's
    // annotations — the same indirection `OperatorThroughputBenchmark` and
    // `BoundedReadFixtures`' `E1_*` constants use, and for the identical reason: a
    // renderer recording the `RunEnvironment` of a sweep must not be able to disagree
    // with the configuration the benchmark actually ran under, and raising the sample
    // means editing this block, never passing `-f`/`-wi`/`-i` at the command line.
    //
    // 5 forks / 5 warmup + 5 measurement iterations of 1 s is this repository's
    // convention, raised on computenet-x9e.6.4 from an earlier 1-fork/3-warmup shape —
    // see `BoundedReadFixtures`' own comment block for the two measured reasons
    // (fork-to-fork variance invisible to a single fork; a flag-raised sample disagreeing
    // with the constants a renderer reads).

    /** [FanOutScalingBenchmark]'s JMH mode: per-delta latency. */
    const val JMH_MODE: String = "AverageTime"

    /** Forks. */
    const val FORKS: Int = 5

    /** Warmup iterations. */
    const val WARMUP_ITERATIONS: Int = 5

    /** Measurement iterations. */
    const val MEASUREMENT_ITERATIONS: Int = 5

    /** Seconds per iteration. */
    const val ITERATION_SECONDS: Int = 1

    /** A host plus the two things only its drive knows: how to settle it, and how to stop it. */
    private class RigHost(val host: ManagedHost, val drain: () -> Unit, val stop: () -> Unit)

    private fun rigHost(degree: FanDegree, drive: Drive): RigHost = when (drive) {
        Drive.SIM -> {
            // No seed: one host, so there is nothing for the controller's cross-host RNG
            // to choose between — the drain order is fixed either way (Graphs.kt's own
            // reasoning for its identical SIM rig).
            val world = SimWorld()
            RigHost(world.host, { world.runToIdle() }, {})
        }

        Drive.REAL -> {
            val scheduler = VirtualThreadScheduler("bench-fanout-${degree.subscribers}")
            val host = ManagedHost(scheduler = scheduler)
            RigHost(
                host,
                { scheduler.awaitDrained("fan-out rig degree=${degree.subscribers}", DRAIN_TIMEOUT_MS) },
                { scheduler.shutdown() },
            )
        }
    }

    /**
     * Build the `source -> {collector x degree.subscribers}` fan-out rig on a host driven
     * by [drive].
     *
     * The returned rig is quiescent and unseeded: nothing has been added yet, so
     * [FanOutRig.elementsAdded] starts at zero and the first [FanOutRig.applyOneAndQuiesce]
     * call is the rig's first real delta.
     *
     * @param wiring [Wiring.UNLINKED] is the fixture's negative control — see this file's
     *   header for why it is [Graphs]'s enum rather than a redeclared one. A rig built
     *   `UNLINKED` spawns every collector but connects none of them, so `applyOneAndQuiesce`
     *   still returns cleanly and [FanOutRig.totalArrivals] must stay zero — the same
     *   property `BoundedReadFixtures.rig`'s `RigWiring.UNLINKED` and `Graphs.build`'s own
     *   `Wiring.UNLINKED` each exist to make assertable rather than assumed.
     * @param preSeed when positive, [FanOutRig.seed] is called with this count before the
     *   rig is returned, off any timer — a caller measuring per-delta cost at a FIXED
     *   source size (computenet-252t) passes the same [preSeed] at every [degree] so the
     *   one thing that varies across a sweep is the fan-out width, not the state size the
     *   original per-iteration rebuild left uncontrolled. Zero (the default) preserves
     *   every existing caller's behaviour: an unseeded rig, exactly as before.
     */
    fun rig(
        degree: FanDegree,
        drive: Drive,
        wiring: Wiring = Wiring.LINKED,
        preSeed: Int = 0,
    ): FanOutRig {
        val built = rigHost(degree, drive)
        val host = built.host

        lateinit var sourceRef: CellRef
        val collectors = ArrayList<ArrivalCollectorCell>(degree.subscribers)

        graph(host.managementInlet) {
            val source = spawn("source") { ref -> SetCell<Int>(ref) }
            sourceRef = source.ref
            repeat(degree.subscribers) { i ->
                val collector = spawn("collector-$i") { ref -> ArrivalCollectorCell(ref) }
                collectors += collector.cell
                // Every collector links to the SAME source outlet — this is the fan-out
                // under test. Link only under LINKED, exactly as Graphs.kt's own `wire`
                // helper does for its negative control.
                if (wiring == Wiring.LINKED) link(source.cell.outlet, collector.cell.inlet)
            }
        }
        built.drain()

        val sourceApi = host.lookup<SetApi<Int>>(sourceRef)
            ?: error("fan-out source $sourceRef not hosted after spawn — the build never completed")

        val rig = FanOutRig(
            degree = degree,
            drive = drive,
            collectors = collectors,
            sourceApi = sourceApi,
            drain = built.drain,
            stop = built.stop,
        )
        if (preSeed > 0) rig.seed(preSeed)
        return rig
    }

    // =====================================================================================
    // computenet-252t — the fixed-state variant's own constants.
    //
    // BS-8's landed sweep (`FanOutFixtures.rig` above, unseeded, rebuilt once per JMH
    // ITERATION) leaves the source's element count to drift with however many invocations
    // one 1s iteration fits — inversely proportional to the per-delta cost being measured,
    // so low-degree rows average over a source 4x-19x larger than high-degree rows (see the
    // 2026-08-19 fan-out entry's "confound" section in doc/bench/findings.md, and this
    // file's own header). `FanOutScalingBenchmark.simFixedState`/`.realFixedState` (bench/src/jmh/kotlin) holds the
    // state size FIXED by rebuilding and re-seeding to [FIXED_STATE_ELEMENTS] once per
    // INVOCATION under `Mode.SingleShotTime` — the bead's first candidate shape — so every
    // degree's single measured delta is applied against the SAME source size.
    //
    // Separate JMH knobs from [FORKS]/[WARMUP_ITERATIONS]/[MEASUREMENT_ITERATIONS] above:
    // those describe `Mode.AverageTime` iterations of 1s wall-clock each, which has no
    // meaning for `Mode.SingleShotTime` (each "iteration" IS one invocation, and the cost
    // per invocation here is dominated by rebuilding a fresh rig and re-seeding it, not by
    // the one measured delta). Sized so the sweep fits a single dispatch slot: at
    // `FIXED_STATE_ELEMENTS=10_000` and the widest fan-out (`FanDegree.D256`), one rebuild
    // + seed is on the order of a few hundred milliseconds (10,000 adds, each fanning to
    // 256 collectors); `FIXED_STATE_FORKS` x
    // (`FIXED_STATE_WARMUP_ITERATIONS` + `FIXED_STATE_MEASUREMENT_ITERATIONS`) single shots
    // per degree/drive combination keeps the ten-combination sweep to low minutes rather
    // than the 3-34h a `Reportable` classification of the ORIGINAL AverageTime sweep would
    // have needed (that sizing arithmetic is in the 2026-08-19 entry).
    // =====================================================================================

    /**
     * The fixed source size every [FanDegree] is measured at, in
     * `FanOutScalingBenchmark`'s `simFixedState`/`realFixedState` — one order of magnitude below `Footprint.kt`'s
     * `Scale.N1E4`, chosen for the identical reason: large enough that the confound's own
     * arithmetic (a 4x-19x swing between D1 and D256 under the ORIGINAL unseeded sweep)
     * cannot recur — the size is IDENTICAL at every degree by construction, not merely
     * large — while small enough that re-seeding it once per invocation, at the widest
     * fan-out, still fits a dispatch slot.
     */
    const val FIXED_STATE_ELEMENTS: Int = 10_000

    /** Forks, `Mode.SingleShotTime`. */
    const val FIXED_STATE_FORKS: Int = 3

    /** Warmup single shots per fork. */
    const val FIXED_STATE_WARMUP_ITERATIONS: Int = 5

    /** Measurement single shots per fork. */
    const val FIXED_STATE_MEASUREMENT_ITERATIONS: Int = 10

    // =====================================================================================
    // computenet-2scd — the BATCH fixed-state variant's own constants.
    //
    // The `Mode.SingleShotTime` shape above closed the state-size confound by construction
    // and paid for it in precision: `doc/bench/findings.md`'s 2026-08-20 fan-out entry
    // records relative dispersion 0.0941-0.2411 against 0.0226-0.0788 in the ORIGINAL
    // AverageTime sweep, so only ONE of four segments per drive resolved against its own
    // 99.9% error bar and the reading came out INCONCLUSIVE. Its own "what would settle it"
    // paragraph names the second candidate shape, which these constants configure: an
    // `@OperationsPerInvocation` batch of FIXED size against a pre-seeded source, with the
    // rig rebuilt per invocation-BATCH rather than per invocation.
    //
    // Why that should recover precision without reopening the confound:
    //
    // - **Precision.** `Mode.SingleShotTime` reports one op per sample; each measurement
    //   iteration IS one cold delta. The batch shape reports the MEAN of [BATCH_OPS] ops
    //   per invocation and many invocations per timed iteration, so a JMH iteration mean
    //   here averages thousands of ops rather than one, and JIT state built up over a
    //   fork's earlier batches is still hot when the next batch runs.
    // - **The confound stays closed.** Every degree runs the SAME [BATCH_OPS] against the
    //   SAME [FIXED_STATE_ELEMENTS] pre-seed. The source does grow WITHIN a batch — from
    //   [FIXED_STATE_ELEMENTS] to [FIXED_STATE_ELEMENTS] + [BATCH_OPS] — but that drift is
    //   identical at every degree BY CONSTRUCTION, which is exactly what the original
    //   per-iteration rebuild could not promise (there the drift was inversely proportional
    //   to the cost being measured, 4x-19x between D1 and D256). [BATCH_OPS] is deliberately
    //   two orders of magnitude below [FIXED_STATE_ELEMENTS] so the intra-batch drift is a
    //   couple of percent of the source, not a factor.
    //
    // `Level.Invocation` `@Setup` under `Mode.AverageTime` is the trap
    // `FixedStateRigState`'s KDoc names, and it is entered DELIBERATELY here with its cost
    // understood rather than assumed away: JMH's iteration loop terminates on WALL CLOCK,
    // and the per-invocation rebuild+re-seed counts against that clock while being excluded
    // from the timed region. So a [BATCH_ITERATION_SECONDS]-second iteration fits fewer
    // invocations than its measured time alone would suggest, and the sweep's wall clock is
    // bounded by the iteration budget regardless — which is what makes it sizeable at all.
    // The measured number is unaffected; only the sample count per iteration is.
    // =====================================================================================

    /**
     * Measured deltas per invocation in the batch shape — the value
     * `FanOutScalingBenchmark.simBatchFixedState`/`.realBatchFixedState` declare as
     * `@OperationsPerInvocation`, so a reported `us/op` is per DELTA and not per batch.
     *
     * 200 against [FIXED_STATE_ELEMENTS] = 10,000 is a 2% intra-batch source drift,
     * identical at every [FanDegree] — see this block's header for why that bound matters
     * and why it is not the confound returning.
     */
    const val BATCH_OPS: Int = 200

    /** Forks, batch shape. */
    const val BATCH_FORKS: Int = 3

    /** Warmup iterations per fork, batch shape. */
    const val BATCH_WARMUP_ITERATIONS: Int = 3

    /** Measurement iterations per fork, batch shape. */
    const val BATCH_MEASUREMENT_ITERATIONS: Int = 6

    /**
     * Seconds per iteration, batch shape.
     *
     * With [BATCH_FORKS] x ([BATCH_WARMUP_ITERATIONS] + [BATCH_MEASUREMENT_ITERATIONS])
     * iterations across ten degree/drive combinations, one second per iteration bounds the
     * sweep's measured wall clock at roughly 4.5 minutes plus fork startup — sized to fit a
     * dispatch slot alongside the render step. It is NOT the budget the
     * `Mode.SingleShotTime` sweep spent: that sweep ran in 47 s wall clock, so this shape
     * spends roughly 6x more, and any precision comparison between the two shapes has to
     * say so rather than read the improvement as the batch shape's alone.
     */
    const val BATCH_ITERATION_SECONDS: Int = 1
}
