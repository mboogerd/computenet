package civictech.bench.micro

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.CountCell
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.IntersectSetCell
import civictech.cell.data.op.PresenceCountCell
import civictech.cell.data.op.QuorumSetCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.data.view.SetView
import civictech.cell.graph.graph
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.testkit.SimWorld
import civictech.testkit.awaitDrained
import java.util.UUID

// ---------------------------------------------------------------------------------------
// Micro-benchmark graph fixtures [BEN1-17].
//
// One builder per subject operator, each constructing
//
//     source cell(s) ──> operator under test ──> collector
//
// on a real host, wired through the graph DSL's `link` — i.e. through
// `ManagedHost.connect` -> `LinkAdmission` -> the target-side link handshake, the same
// link/port path an application uses. Deltas enter through a source cell's *inlet*, as a
// hosted invocation on the host queue; the operator's own methods are never called
// directly. That is the whole point of the fixture: a micro-benchmark that called
// `filterCell.onInlet(delta)` would measure a method, not a dataflow graph, and would be
// silently insensitive to everything the port/link/dispatch layer costs.
//
// The per-operator kernel tests (UnionSetCellTest, QuorumSetCellTest, ...) are the
// construction reference and nothing more. Their DRIVE discipline is deliberately NOT
// copied: they exercise unhosted cells on the caller's thread, where "has it settled?"
// is answered by the call having returned. Here the graph is hosted, so quiescence is a
// real question with two real answers — see [Drive].
// ---------------------------------------------------------------------------------------

/**
 * Which scheduler drives a built graph, and therefore what "settled" means.
 *
 * - [SIM] — testkit's [SimWorld] over `SimulationController`: single-threaded and
 *   deterministic, drained with `runToIdle` under a hard step budget. Quiescence is a
 *   fact (no scheduler has work), not an estimate.
 * - [REAL] — a [ManagedHost] on a [VirtualThreadScheduler], drained with testkit's
 *   `HostScheduler.awaitDrained` fence. Read that helper's KDoc before reaching for a
 *   poll instead: two equal samples of an observable value mean "nothing changed during
 *   this window", which a *starved* host produces exactly as readily as a converged one.
 *   The fence submits one task below every band the host uses and waits for it, so its
 *   completion is positive evidence the queue emptied.
 *
 * The two exist side by side because they measure different things: [SIM] isolates the
 * operator's own work from thread hand-off and queue contention; [REAL] is what a
 * deployed host actually costs. A number from one is not a number from the other, and a
 * result that does not say which drive produced it says nothing.
 */
enum class Drive { SIM, REAL }

/**
 * Whether [Graphs.build] actually connects the graph it constructs.
 *
 * [UNLINKED] is the fixture's **negative control**, and it is load-bearing rather than
 * decorative. Every other guarantee here — arrival counts, membership folds — is read at
 * the collector, so a builder that returned a perfectly well-formed graph whose links
 * were never established would report `arrivals == 0`, `live == 0` and *still construct,
 * apply and quiesce without throwing*. A test suite that only asserts "it built" cannot
 * tell that apart from a working fixture. [UNLINKED] makes the difference observable and
 * asserted on every run, instead of resting on a one-off mutation somebody ran once.
 */
enum class Wiring { LINKED, UNLINKED }

/** The payload the subject's collector folds — one collector cell per shape. */
enum class Readout {
    /** `SetDelta<Int>`: folded through the kernel's own [SetView]. */
    SET,

    /** `CounterDelta`: summed. */
    COUNTER,

    /** `MapDelta<Int, Int>`: per-element live-source counts. */
    PRESENCE_MAP,
}

/**
 * The set-shaped operator family — 8 of BEN1's 15 subjects.
 *
 * The sibling extension task adds the join/group-by/combine subjects
 * (`JoinSetCell`, `LookupJoinCell`, `SemiJoinCell`, `GroupByCell`,
 * `MergeableGroupByCell`, `CombineLatestCell`, `CoalescingCombineCell` and the
 * `Aggregator` variants) as further constants here. Two seams make that an
 * extension rather than a reshape:
 *
 *  - [Graphs.build]'s body is a `when` **expression** over this enum, so a new constant
 *    without a branch is a compile error, never a silently unbuildable subject;
 *  - [referenceLive] is stated per subject as the operator's *definition*, so a new
 *    subject brings its own oracle with it.
 *
 * @param sources how many source cells feed the operator — every source receives every
 *   delta of an applied batch (see [MicroGraph.applyBatch]).
 * @param readout the payload shape the collector folds.
 */
enum class Subject(val sources: Int, val readout: Readout) {

    /**
     * The shared unary skeleton, `civictech.cell.data.op.TaggedSetOperator`.
     *
     * **Measured through `FilterCell { true }`, and the limitation is real.**
     * `TaggedSetOperator` is not a `Cell`: it is the composed ledger + emit-or-absorb
     * skeleton that `FilterCell`, `UnionSetCell`, `FlatMapSetCell` and `CountCell` each
     * hold as a field, and its `state` is `internal` to `:kernel`, so `:bench` cannot
     * drive one directly at all. An always-true filter is the thinnest kernel cell whose
     * entire body *is* the skeleton — `TagState.apply` plus `emitOrAbsorb`, with an
     * identity transform in front. So this subject measures the skeleton **plus one
     * always-true predicate call per element**, not the skeleton alone, and the honest
     * use of its number is as the identity baseline that [FILTER]'s own predicate cost is
     * the difference from. It is not a measurement of `TaggedSetOperator` in isolation,
     * and no result derived from it should be reported as one.
     */
    TAGGED_SET(sources = 1, readout = Readout.SET),

    /** `FilterCell` with an even-element predicate: half the stream is absorbed. */
    FILTER(sources = 1, readout = Readout.SET),

    /** `UnionSetCell` — the retaining (tombstone-keeping) tagged-set operator. */
    UNION(sources = 1, readout = Readout.SET),

    /** `IntersectSetCell`, both arms fed the same deltas, so the intersection is the whole set. */
    INTERSECT(sources = 2, readout = Readout.SET),

    /** `CountCell` — the one subject whose output is a `CounterDelta`, not a `SetDelta`. */
    COUNT(sources = 1, readout = Readout.COUNTER),

    /** `FlatMapSetCell` expanding each element to two distinct outputs. */
    FLAT_MAP(sources = 1, readout = Readout.SET),

    /** `PresenceCountCell` over a two-lane fan-in; output is `MapDelta<Int, Int>`. */
    PRESENCE_COUNT(sources = 2, readout = Readout.PRESENCE_MAP),

    /** `QuorumSetCell` at the intersection threshold (`n -> n`) over a two-lane fan-in. */
    QUORUM(sources = 2, readout = Readout.SET),
    ;

    /**
     * What the collector *should* observe as live membership once [elements] have been
     * inserted and nothing retracted — the operator's definition, written independently
     * of the kernel implementation and of the collector fold it is compared against.
     *
     * This is the fixture's oracle. Deriving it from anything the graph computes would
     * make every per-subject test tautological.
     */
    fun referenceLive(elements: Set<Int>): Int = when (this) {
        TAGGED_SET, UNION, INTERSECT, COUNT, PRESENCE_COUNT, QUORUM -> elements.size
        FILTER -> elements.count { PASSES_FILTER(it) }
        FLAT_MAP -> elements.size * FLAT_MAP_FANOUT
    }

    companion object {
        /** The subjects this task builds — all of them today; the extension task appends. */
        fun setShaped(): List<Subject> = values().toList()

        /** [FILTER]'s predicate, shared with [referenceLive] so the two cannot drift. */
        val PASSES_FILTER: (Int) -> Boolean = { it % 2 == 0 }

        /** [FLAT_MAP]'s expansion: each element becomes this many distinct outputs. */
        const val FLAT_MAP_FANOUT: Int = 2
    }
}

/**
 * What a collector cell exposes, uniformly, whatever payload it folds.
 *
 * **Reading these is safe only after quiescence**, and the reason is worth stating: the
 * fields are written by the host's single drain thread and read by the caller's. Under
 * [Drive.SIM] there is one thread and the question does not arise. Under [Drive.REAL],
 * `awaitDrained`'s latch supplies the happens-before edge — every task the host ran
 * precedes the fence task, whose `countDown()` precedes the caller's `await()` return —
 * so a read *after* [MicroGraph.quiesce] sees every write. A read taken mid-flight is
 * racy and meaningless; the API offers no way to want one.
 */
interface Collected {
    /** Number of deltas that arrived at the collector's inlet. */
    val arrivals: Long

    /** Live membership the collector observes, folded from the arrivals it actually got. */
    val live: Int
}

/**
 * The source cell: a delta arrives on its inlet as a hosted invocation and is
 * re-originated verbatim on its outlet.
 *
 * Deliberately not `civictech.cell.data.SetCell`, though that cell has a `deltaInlet`
 * that would accept these batches. `SetCell` is a full OR-set — it holds tag maps under
 * a lock, mints its own tags and folds a delivered frontier — and every bit of that
 * would land inside the measured region while measuring nothing about the operator under
 * test. This forwards and does nothing else, so what a benchmark sees is the host queue,
 * the link, and the operator.
 */
class DeltaSourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Int>>>())
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Int>>>())

    init {
        // originate, not a plain reactive emit: each applied delta is its own wave, so a
        // batch is N waves rather than one long-lived context that would let downstream
        // wave-completeness machinery coalesce work the benchmark meant to count.
        inlet.onEach { delta -> outlet.originate { propagate(delta) } }
    }
}

/**
 * The interface a hosted-proxy lookup reflects over to reach [DeltaSourceCell.inlet].
 * `ManagedHost.lookup` builds a dynamic proxy from a port-typed getter, so this
 * one-property interface is the whole handle a caller needs.
 */
interface DeltaSourceApi {
    val inlet: Use<Propagate<SetDelta<Int>>>
}

/** Collector for `SetDelta<Int>` outputs, folding membership through the kernel's own [SetView]. */
class SetCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Collected {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Int>>>())
    private val view = SetView<Int>()
    private var count = 0L

    override val arrivals: Long get() = count
    override val live: Int get() = view.size

    init {
        inlet.onEach { delta ->
            view.apply(delta)
            count++
        }
    }
}

/** Collector for `CounterDelta` outputs ([Subject.COUNT]); live membership is the running sum. */
class CounterCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Collected {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())
    private var count = 0L
    private var sum = 0L

    override val arrivals: Long get() = count
    override val live: Int get() = sum.toInt()

    init {
        inlet.onEach { delta ->
            sum += delta.amount
            count++
        }
    }
}

/**
 * Collector for `MapDelta<Int, Int>` outputs ([Subject.PRESENCE_COUNT]); live membership
 * is the number of elements currently carrying a non-zero live-source count, which is
 * exactly the map's size once removals are applied (`PresenceCountCell` emits a removal
 * when a count drops to 0).
 */
class PresenceCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Collected {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<Int, Int>>>())
    private val counts = LinkedHashMap<Int, Int>()
    private var count = 0L

    override val arrivals: Long get() = count
    override val live: Int get() = counts.size

    init {
        inlet.onEach { delta ->
            counts.putAll(delta.puts)
            delta.removals.forEach { counts.remove(it) }
            count++
        }
    }
}

/**
 * One built, hosted, drivable operator graph — the single API every BEN1 micro-benchmark
 * and every fixture test goes through.
 *
 * Lifecycle: [Graphs.build] → repeated ([applyBatch] → [quiesce]) → read [arrivals] /
 * [live] → [close]. A graph is single-threaded from the caller's side: apply and drain
 * from one thread (under [Drive.SIM] this is mandatory — the simulation controller's
 * determinism *is* its single-threadedness).
 */
class MicroGraph internal constructor(
    val subject: Subject,
    val drive: Drive,
    val wiring: Wiring,
    private val sourceInlets: List<Use<Propagate<SetDelta<Int>>>>,
    private val collected: Collected,
    private val drain: () -> Unit,
    private val stop: () -> Unit,
) : AutoCloseable {

    /** How many source cells feed the operator; equals `subject.sources`. */
    val sourceCount: Int get() = sourceInlets.size

    /** Deltas observed at the collector. Read after [quiesce] — see [Collected]. */
    val arrivals: Long get() = collected.arrivals

    /** Live membership observed at the collector. Read after [quiesce] — see [Collected]. */
    val live: Int get() = collected.live

    /**
     * Apply a pre-generated batch: every delta is pushed into **every** source inlet, in
     * batch order, as a hosted invocation. So a batch of N deltas on a two-source subject
     * costs 2N host invocations — the multi-source subjects genuinely do more work per
     * batch, and a result comparing them across arities has to say so.
     *
     * Returns without waiting: the deltas are queued, not necessarily processed. Call
     * [quiesce] before reading anything.
     */
    fun applyBatch(batch: DeltaBatch) {
        batch.deltas.forEach { delta ->
            sourceInlets.forEach { it.call.propagate(delta) }
        }
    }

    /** Drive the host to quiescence: `runToIdle` under [Drive.SIM], the drain fence under [Drive.REAL]. */
    fun quiesce() = drain()

    /** [applyBatch] then [quiesce] — the shape a measured benchmark body uses. */
    fun applyAndQuiesce(batch: DeltaBatch) {
        applyBatch(batch)
        quiesce()
    }

    /** Release the drive's resources (stops the [Drive.REAL] scheduler thread; a no-op under [Drive.SIM]). */
    override fun close() = stop()
}

/** Builders for the subject graphs [BEN1-17]. */
object Graphs {

    /** A host plus the two things only its drive knows: how to settle it, and how to stop it. */
    private class Rig(val host: ManagedHost, val drain: () -> Unit, val stop: () -> Unit)

    private fun rig(subject: Subject, drive: Drive): Rig = when (drive) {
        Drive.SIM -> {
            // No seed: one host, so there is nothing for the controller's cross-host RNG
            // to choose between — the drain order is fixed either way.
            val world = SimWorld()
            Rig(world.host, { world.runToIdle() }, { })
        }

        Drive.REAL -> {
            val scheduler = VirtualThreadScheduler("bench-micro-${subject.name.lowercase()}")
            val host = ManagedHost(scheduler = scheduler)
            Rig(host, { scheduler.awaitDrained("micro graph ${subject.name}") }, { scheduler.shutdown() })
        }
    }

    /**
     * Build [subject]'s graph on a host driven by [drive].
     *
     * The returned graph is already quiescent: spawning and linking have been driven to
     * completion, so the first [MicroGraph.applyBatch] measures delta flow and not
     * construction.
     */
    fun build(subject: Subject, drive: Drive, wiring: Wiring = Wiring.LINKED): MicroGraph {
        val rig = rig(subject, drive)
        val host = rig.host

        lateinit var sourceRefs: List<CellRef>
        lateinit var collected: Collected

        graph(host.managementInlet) {
            // Link only under LINKED. Everything else about the graph — the cells, their
            // refs, the host, the drive — is identical, which is what makes UNLINKED a
            // control for the links specifically rather than for construction in general.
            fun <A> wire(out: Subscribe<A>, inn: Serve<A>) {
                if (wiring == Wiring.LINKED) link(out, inn)
            }

            val srcs = (0 until subject.sources).map { i -> spawn("source-$i") { ref -> DeltaSourceCell(ref) } }
            sourceRefs = srcs.map { it.ref }

            // A `when` EXPRESSION over the enum: the extension task cannot add a subject
            // without adding its branch here, because the compiler will not let it.
            collected = when (subject) {
                Subject.TAGGED_SET -> {
                    val op = spawn("op") { ref -> FilterCell<Int>(ref = ref, predicate = { true }) }
                    val out = spawn("collector") { ref -> SetCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.FILTER -> {
                    val op = spawn("op") { ref ->
                        FilterCell(ref = ref, predicate = Subject.PASSES_FILTER)
                    }
                    val out = spawn("collector") { ref -> SetCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.UNION -> {
                    val op = spawn("op") { ref -> UnionSetCell<Int>(ref) }
                    val out = spawn("collector") { ref -> SetCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.INTERSECT -> {
                    val op = spawn("op") { ref -> IntersectSetCell<Int>(ref) }
                    val out = spawn("collector") { ref -> SetCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.left)
                    wire(srcs[1].cell.outlet, op.cell.right)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.COUNT -> {
                    val op = spawn("op") { ref -> CountCell<Int>(ref) }
                    val out = spawn("collector") { ref -> CounterCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.FLAT_MAP -> {
                    val op = spawn("op") { ref ->
                        // Two distinct outputs per input: `2a` and `2a+1` never collide
                        // across distinct `a`, so the expansion is exactly FLAT_MAP_FANOUT
                        // and the oracle stays a multiplication.
                        FlatMapSetCell(ref = ref, f = { a: Int -> listOf(2 * a, 2 * a + 1) })
                    }
                    val out = spawn("collector") { ref -> SetCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.PRESENCE_COUNT -> {
                    val op = spawn("op") { ref -> PresenceCountCell<Int>(ref) }
                    val out = spawn("collector") { ref -> PresenceCollectorCell(ref) }
                    // Both sources land on the one fan-in inlet: two links, two lanes.
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(srcs[1].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.QUORUM -> {
                    // Intersection threshold: every live source must assert the element.
                    val op = spawn("op") { ref -> QuorumSetCell<Int>(ref) { n -> n } }
                    val out = spawn("collector") { ref -> SetCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(srcs[1].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }
            }
        }

        rig.drain()

        val inlets = sourceRefs.map { ref ->
            val api = host.lookup<DeltaSourceApi>(ref)
                ?: error("source cell $ref not hosted after spawn — the build never completed")
            api.inlet
        }
        return MicroGraph(subject, drive, wiring, inlets, collected, rig.drain, rig.stop)
    }
}
