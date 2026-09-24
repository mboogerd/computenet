package civictech.timetravel.reconstruct

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.op.UnionSetCell
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.graph
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import java.io.Serializable
import java.util.UUID

/**
 * A durable `sources -> union -> view` graph, driven and recorded by a real
 * `ManagedHost` (computenet-6tm33 D2/D9 breakdown, "the fixture every
 * reconstruction test uses"): every reconstruction test in this feature
 * family builds its input by [record]ing a live run, never by hand-assembling
 * journal bytes.
 */
object DurableGraphFixture {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    /** The [CellRef]s a [record] run spawns, fixed by `seed` and `sourceCount`. */
    class Refs(seed: Long, sourceCount: Int) {
        val sources: List<CellRef> = (0 until sourceCount).map { CellRef(UUID(seed, it.toLong())) }
        val union: CellRef = CellRef(UUID(seed, 100))
        val view: CellRef = CellRef(UUID(seed, 101))

        /** Every ref [record] spawns: sources, then union, then view. */
        val all: Set<CellRef> get() = (sources + listOf(union, view)).toSet()
    }

    /**
     * One scripted `add` and what it did: [proxyIndex] is the journal length
     * just before the add was issued (the record it will occupy, if the
     * source cell is journaled); [lastIndex] is the journal length minus one
     * just after the drive settled (the last record this step wrote — only
     * source frames are journaled here, locally-linked downstream deliveries
     * never are, so this equals [proxyIndex]; see GraphSpecSourceTest's
     * record-shape test); [liveSnapshots]
     * is every graph cell's [Stateful.snapshot] right after this step.
     */
    data class Step(
        val sourceIndex: Int,
        val element: String,
        val proxyIndex: Int,
        val lastIndex: Int,
        val liveSnapshots: Map<CellRef, Serializable>,
    )

    /**
     * One live [record] run: the [journal] it wrote (or was handed empty and
     * filled), the [spec] that built its topology (replayable by
     * [GraphSpecSource]), its [refs], the per-add [steps], and each source
     * outlet's live emission-epoch [epochs] (6tm33-D9's comparison target).
     */
    class Recording(
        val journal: Journal,
        val spec: GraphSpec,
        val refs: Refs,
        val steps: List<Step>,
        val epochs: List<UUID>,
    )

    /**
     * Builds `sourceCount` [SetCell]s feeding one [UnionSetCell] ("union")
     * feeding a second [UnionSetCell] ("view"), on a durable, deterministic
     * host, then drives [script] — `(sourceIndex, element)` pairs, each an
     * `add` through a [HostedCellProxy] (never a direct in-process call: the
     * proxy carries no [civictech.cell.MessageContext], matching how every
     * other durability fixture in this repo drives a source). After
     * `checkpointAfter` scripted steps complete (1-based), [ManagedHost.checkpoint]
     * runs once, mid-script, against [journal].
     */
    fun record(
        seed: Long,
        sourceCount: Int,
        script: List<Pair<Int, String>>,
        journal: Journal = InMemoryJournal(),
        checkpointAfter: Int? = null,
    ): Recording {
        val refs = Refs(seed, sourceCount)
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, journal = journal)

        lateinit var sourceCells: List<SetCell<String>>
        lateinit var unionCell: UnionSetCell<String>
        lateinit var viewCell: UnionSetCell<String>

        val spec = graph(host.managementInlet) {
            val sourceHandles = refs.sources.mapIndexed { i, ref ->
                spawn("s$i", IdentityBinding.Exact(ref)) { r -> SetCell<String>(r) }
            }
            val unionHandle = spawn("union", IdentityBinding.Exact(refs.union)) { r -> UnionSetCell<String>(r) }
            val viewHandle = spawn("view", IdentityBinding.Exact(refs.view)) { r -> UnionSetCell<String>(r) }
            sourceHandles.forEach { connect(it, "outlet", unionHandle, "inlet") }
            connect(unionHandle, "outlet", viewHandle, "inlet")
            sourceCells = sourceHandles.map { it.cell }
            unionCell = unionHandle.cell
            viewCell = viewHandle.cell
        }
        controller.runToIdle()

        val proxies = refs.sources.map { ref ->
            @Suppress("UNCHECKED_CAST")
            (HostedCellProxy.create(ref, host, SetInletProxy::class.java) as SetInletProxy).inlet.call
        }

        val allCells: List<Cell> = sourceCells + unionCell + viewCell
        val steps = mutableListOf<Step>()
        script.forEachIndexed { i, (sourceIndex, element) ->
            val proxyIndex = journal.replay().size
            proxies[sourceIndex].add(element)
            controller.runToIdle()
            val lastIndex = journal.replay().size - 1
            steps += Step(sourceIndex, element, proxyIndex, lastIndex, snapshotsOf(allCells))
            if (checkpointAfter != null && i + 1 == checkpointAfter) host.checkpoint(journal)
        }

        val epochs = sourceCells.map { cell ->
            (PortRegistry.of(cell)["outlet"] as FanOutlet<*>).waveState().sourceId
        }

        return Recording(journal, spec, refs, steps, epochs)
    }

    /** Every [cell]'s [Stateful.snapshot], keyed by ref — every cell here is [Stateful]. */
    fun snapshotsOf(cells: Collection<Cell>): Map<CellRef, Serializable> =
        cells.associate { it.ref to (it as Stateful).snapshot() }

    /**
     * Recovers [recording] LIVE — fresh registry, fresh `SimulationController(seed)`,
     * a **copy** of [recording]'s journal (`InMemoryJournal().apply { reset(recording.journal.replay()) }`,
     * so a post-replay re-emission never appends to the recording) — by
     * reapplying [Recording.spec] and calling [ManagedHost.recoverFrom] the
     * ordinary way. Deliberately uses `applyTo` + `snapshotOf`, not
     * [GraphSpecSource], so a test comparing this against an offline
     * reconstruction compares two independent constructions of the same
     * graph (BS-3).
     */
    fun recoverLive(recording: Recording, seed: Long = 0): Map<CellRef, Serializable> {
        val registry = LocationRegistry()
        val controller = SimulationController(seed)
        val copy = InMemoryJournal().apply { reset(recording.journal.replay()) }
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, journal = copy)
        recording.spec.applyTo(host.managementInlet)
        controller.runToIdle()
        host.recoverFrom(copy)
        controller.runToIdle()
        val futures = recording.refs.all.associateWith { host.snapshotOf(it) }
        controller.runToIdle()
        return futures.mapValues { (_, future) -> future.get() }.filterValues { it != null }
            .mapValues { (_, value) -> value as Serializable }
    }
}
