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
     */
    fun rig(degree: FanDegree, drive: Drive, wiring: Wiring = Wiring.LINKED): FanOutRig {
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

        return FanOutRig(
            degree = degree,
            drive = drive,
            collectors = collectors,
            sourceApi = sourceApi,
            drain = built.drain,
            stop = built.stop,
        )
    }
}
