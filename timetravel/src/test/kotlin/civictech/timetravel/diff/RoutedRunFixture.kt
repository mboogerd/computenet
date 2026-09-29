package civictech.timetravel.diff

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
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
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalReading
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.reconstruct.DurableGraphFixture
import civictech.timetravel.reconstruct.GraphSource
import civictech.timetravel.reconstruct.GraphSpecSource
import civictech.timetravel.reconstruct.Reconstructor
import civictech.timetravel.timeline.RunTimeline
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * A `sources -> union -> view` run on a whole durable host whose edges are **routed** through
 * `HostedCellProxy` intake, so every union and view frame is journaled with a wave (`si0tl-D13`).
 *
 * A copy of `FrontierCutReconstructionTest`'s private `recordRouted`/`route`/`routedSource` (that
 * class's KDoc explains why local `connect`s journal nothing downstream), with the source count and
 * the journal made parameters so a `FileJournal` can be recorded. Per scripted add the journal
 * gains exactly three records — the source's contextless proxy frame, then the union's and the
 * view's waved frames — asserted in [record] as the original does.
 */
object RoutedRunFixture {

    /** The union/view inlet shape, routed through a host: `Propagate<SetDelta<String>>`. */
    interface DeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    class Recording(
        val journal: Journal,
        val spec: GraphSpec,
        val refs: DurableGraphFixture.Refs,
        /** `(element, proxyIndex)` per scripted add: the journal length just before the add. */
        val adds: List<Pair<String, Int>>,
        /** Each source outlet's live emission epoch — the `sourceId` of its waved frames. */
        val epochs: List<UUID>,
    )

    /** `upstream.outlet -> downstream.inlet` as a routed edge through [host]'s intake. */
    private fun route(host: ManagedHost, upstream: Cell, downstream: CellRef) {
        val use = (HostedCellProxy.create(downstream, host, DeltaInletProxy::class.java) as DeltaInletProxy).inlet
        @Suppress("UNCHECKED_CAST")
        (PortRegistry.of(upstream)["outlet"] as FanOutlet<Propagate<SetDelta<String>>>).subscribe(use)
    }

    private fun routeAll(host: ManagedHost, refs: DurableGraphFixture.Refs, cells: Collection<Cell>) {
        val byRef = cells.associateBy { it.ref }
        refs.sources.forEach { route(host, byRef.getValue(it), refs.union) }
        route(host, byRef.getValue(refs.union), refs.view)
    }

    /** The spawn-only [GraphSpecSource] of [recording], with the routed edges re-applied. */
    fun graphSource(recording: Recording): GraphSource = GraphSource { host ->
        GraphSpecSource(recording.spec).build(host).also { routeAll(host, recording.refs, it.cells) }
    }

    fun record(
        seed: Long,
        sourceCount: Int,
        script: List<Pair<Int, String>>,
        journal: Journal = InMemoryJournal(),
    ): Recording {
        val refs = DurableGraphFixture.Refs(seed, sourceCount)
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler(), registry = LocationRegistry(), journal = journal)
        lateinit var cells: List<Cell>
        val spec = graph(host.managementInlet) {
            val sources = refs.sources.mapIndexed { i, ref ->
                spawn("s$i", IdentityBinding.Exact(ref)) { r -> SetCell<String>(r) }
            }
            val union = spawn("union", IdentityBinding.Exact(refs.union)) { r -> UnionSetCell<String>(r) }
            val view = spawn("view", IdentityBinding.Exact(refs.view)) { r -> UnionSetCell<String>(r) }
            cells = sources.map { it.cell } + union.cell + view.cell
        }
        controller.runToIdle()
        routeAll(host, refs, cells)

        val proxies = refs.sources.map { ref ->
            (HostedCellProxy.create(ref, host, DurableGraphFixture.SetInletProxy::class.java)
                as DurableGraphFixture.SetInletProxy).inlet.call
        }
        val adds = script.map { (sourceIndex, element) ->
            val proxyIndex = journal.replay().size
            proxies[sourceIndex].add(element)
            controller.runToIdle()
            // The routed shape, observed rather than assumed: exactly three records per add.
            journal.replay().size shouldBe proxyIndex + 3
            element to proxyIndex
        }
        val epochs = cells.take(sourceCount).map { (PortRegistry.of(it)["outlet"] as FanOutlet<*>).waveState().sourceId }
        return Recording(journal, spec, refs, adds, epochs)
    }

    /** An in-memory reading of [recording]'s journal, labelled [label]. */
    fun reading(recording: Recording, label: String): JournalReading =
        JournalReader.open(JournalSource.InMemory(recording.journal, label))

    /** The single timeline of a one-journal [reading]. */
    fun timeline(reading: JournalReading): RunTimeline = RunTimeline.of(reading).values.single()

    /** A reconstructor of [recording]'s run over [graphSource]. */
    fun reconstructor(recording: Recording, reading: JournalReading, timeline: RunTimeline): Reconstructor =
        Reconstructor(reading, timeline, graphSource(recording))
}
