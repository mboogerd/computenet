package civictech.timetravel.reconstruct

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.UnionSetCell
import civictech.cell.durability.InMemoryJournal
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
import civictech.timetravel.fidelity.Fidelity
import civictech.timetravel.journal.FrameRecord
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import civictech.timetravel.timeline.TimelinePosition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/**
 * TTD1 F4 (`computenet-6tm33.6`) BS-2, `[TTD1-18]` host half: reconstruction at a per-source
 * frontier cut replays exactly the longest journal prefix whose waved frames all lie at or behind
 * the cut, and the union's and view's state equals the batch fold of that prefix's adds.
 *
 * ## Why this file records its own graph (AMENDS computenet-6tm33.6)
 *
 * `DurableGraphFixture` links `source -> union -> view` with local `connect`s, which subscribe
 * in-process: a downstream delivery never reaches `ManagedHost.enqueueHostedInvocation` (the only
 * journal-append site), so that fixture's journal holds only the sources' contextless proxy
 * frames and has no waved frame to cut on. Here the same cells are linked by **routed** edges —
 * each upstream `FanOutlet` subscribes a `HostedCellProxy` `Use` of the downstream inlet, whose
 * `call` builds a `PORT_API` `HostedPortInvocation` stamped with the ambient `CurrentContext` and
 * hands it to the host's intake. The outlet's emission frame is that context
 * (`Timestamp(outlet epoch, counter)`), so every union and view frame is journaled **waved**, and
 * the journal reads, per scripted add: the source's contextless proxy frame, the union's waved
 * frame, the view's waved frame (the three-records-per-add count is asserted in [recordRouted]).
 * No kernel change: a routed proxy edge is an ordinary single-host shape.
 *
 * The reconstruction host gets the same routed edges from [routedSource], a [GraphSource] that
 * applies the spawn-only [GraphSpec] through [GraphSpecSource] and routes its returned cells.
 */
class FrontierCutReconstructionTest {

    /** The union/view inlet shape, routed through a host: `Propagate<SetDelta<String>>`. */
    interface DeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    private class RoutedRecording(
        val journal: InMemoryJournal,
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

    private fun routedSource(recording: RoutedRecording) = GraphSource { host ->
        GraphSpecSource(recording.spec).build(host).also { routeAll(host, recording.refs, it.cells) }
    }

    private fun recordRouted(seed: Long, script: List<Pair<Int, String>>): RoutedRecording {
        val refs = DurableGraphFixture.Refs(seed, 2)
        val journal = InMemoryJournal()
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
        val epochs = cells.take(2).map { (PortRegistry.of(it)["outlet"] as FanOutlet<*>).waveState().sourceId }
        return RoutedRecording(journal, spec, refs, adds, epochs)
    }

    private fun reconstructorOf(recording: RoutedRecording): Pair<Reconstructor, RunTimeline> {
        val reading = JournalReader.open(JournalSource.InMemory(recording.journal, "j"))
        val timeline = RunTimeline.of(reading).getValue("j")
        return Reconstructor(reading, timeline, routedSource(recording)) to timeline
    }

    /** The union's waved frames, in journal order. */
    private fun unionWaved(timeline: RunTimeline, recording: RoutedRecording): List<TimelinePosition> =
        timeline.positions.filter { (it.record as? FrameRecord)?.cellRef == recording.refs.union && it.wave != null }

    /** The `k`-th (1-based) union frame waved by [epoch]. */
    private fun List<TimelinePosition>.nth(epoch: UUID, k: Int): TimelinePosition =
        filter { it.wave!!.sourceId == epoch }[k - 1]

    private fun Timestamp.atOrBehind(cut: Map<UUID, Long>): Boolean = counter <= (cut[sourceId] ?: -1L)

    /**
     * A reconstructed set cell's members: a `SetCell` snapshot keeps them under `"adds"`, a
     * `UnionSetCell` snapshot is the element -> tags map itself.
     */
    private fun membership(cell: CellReconstruction): Set<String> {
        val reconstructed = cell.shouldBeInstanceOf<CellReconstruction.Reconstructed>()
        val snapshot: Serializable = reconstructed.snapshot
        val map = snapshot.shouldBeInstanceOf<Map<*, *>>()
        val members = if (reconstructed.cellClass == SetCell::class.java.name) map["adds"] as Map<*, *> else map
        return members.keys.mapTo(mutableSetOf()) { it as String }
    }

    private fun assertAllFaithful(reconstruction: Reconstruction, recording: RoutedRecording) {
        reconstruction.cells.keys shouldBe recording.refs.all
        for ((_, cell) in reconstruction.cells) {
            cell.shouldBeInstanceOf<CellReconstruction.Reconstructed>().fidelity shouldBe Fidelity.Faithful
        }
        reconstruction.run shouldBe Fidelity.Faithful
        reconstruction.details.shouldBeEmpty()
    }

    @Test
    fun `BS-2 a per-source cut replays every waved frame at or behind it and none beyond, and folds the prefix's adds`() {
        val script = listOf(0 to "a1", 0 to "a2", 1 to "b1", 0 to "a3", 1 to "b2", 0 to "a4")
        val recording = recordRouted(seed = 21, script = script)
        val (reconstructor, timeline) = reconstructorOf(recording)
        val (a, b) = recording.epochs
        timeline.sources shouldBe setOf(a, b)

        val union = unionWaved(timeline, recording)
        union shouldHaveSize 6
        val cut = mapOf(a to union.nth(a, 3).wave!!.counter, b to union.nth(b, 1).wave!!.counter)

        val reconstruction = reconstructor.stateAt(Position.Cut(cut))
        val n = reconstruction.position.prefixEnd

        n shouldBe union.nth(b, 2).index
        reconstruction.position shouldBe ResolvedPosition(Position.Cut(cut), n, 0, n)
        // [TTD1-18]: nothing in the prefix lies beyond the cut ...
        timeline.positions.take(n).mapNotNull { it.wave }.forEach { it.atOrBehind(cut) shouldBe true }
        // ... and every waved frame at or behind the cut (union AND view) is inside it.
        val atOrBehind = timeline.positions.filter { it.wave?.atOrBehind(cut) == true }
        atOrBehind shouldHaveSize 8 // (A,1..3) and (B,1), each for union and view
        atOrBehind.forEach { it.index shouldBeLessThan n }

        // The batch fold of the resolved prefix's adds: b2's contextless proxy frame precedes
        // its waved union frame, so b2 is in; a4 is not.
        val folded = recording.adds.filter { (_, proxyIndex) -> proxyIndex < n }.mapTo(mutableSetOf()) { it.first }
        folded shouldBe setOf("a1", "a2", "b1", "a3", "b2")
        membership(reconstruction.cells.getValue(recording.refs.union)) shouldBe folded
        membership(reconstruction.cells.getValue(recording.refs.view)) shouldBe folded
        assertAllFaithful(reconstruction, recording)

        // The waved frames are themselves replayed, not merely re-derived: on the routed host
        // above, each replayed source frame re-propagates, so the fold would come out the same
        // even if no union/view frame were fed. On a host with the same cells but NO edges, a
        // replayed source frame reaches nothing downstream, so the union and view hold exactly
        // the elements of their own waved frames inside [0, n): (A,1..3) and (B,1) — b2's union
        // frame is at n, outside.
        val reading = JournalReader.open(JournalSource.InMemory(recording.journal, "j"))
        val unlinked = Reconstructor(reading, timeline, GraphSpecSource(recording.spec)).stateAt(Position.Cut(cut))
        unlinked.position.prefixEnd shouldBe n
        membership(unlinked.cells.getValue(recording.refs.union)) shouldBe setOf("a1", "a2", "b1", "a3")
        membership(unlinked.cells.getValue(recording.refs.view)) shouldBe setOf("a1", "a2", "b1", "a3")
    }

    @Test
    fun `BS-2 an at-or-behind frame after the first beyond-cut frame is outside the prefix - a prefix, not a filter`() {
        val script = listOf(0 to "a1", 0 to "a2", 1 to "b1", 1 to "b2", 0 to "a3", 0 to "a4")
        val recording = recordRouted(seed = 22, script = script)
        val (reconstructor, timeline) = reconstructorOf(recording)
        val (a, b) = recording.epochs
        val union = unionWaved(timeline, recording)
        val cut = mapOf(a to union.nth(a, 3).wave!!.counter, b to union.nth(b, 1).wave!!.counter)

        val reconstruction = reconstructor.stateAt(Position.Cut(cut))
        val n = reconstruction.position.prefixEnd

        val firstBeyond = union.nth(b, 2)
        val laterAtOrBehind = union.nth(a, 3)
        n shouldBe firstBeyond.index
        // The discriminating frame: at or behind the cut, but journaled after a beyond-cut one.
        laterAtOrBehind.wave!!.atOrBehind(cut) shouldBe true
        laterAtOrBehind.index shouldBeGreaterThan firstBeyond.index
        timeline.positions.take(n).mapNotNull { it.wave }.forEach { it.atOrBehind(cut) shouldBe true }

        val folded = recording.adds.filter { (_, proxyIndex) -> proxyIndex < n }.mapTo(mutableSetOf()) { it.first }
        folded shouldBe setOf("a1", "a2", "b1", "b2")
        membership(reconstruction.cells.getValue(recording.refs.union)) shouldBe folded
        membership(reconstruction.cells.getValue(recording.refs.view)) shouldBe folded
        assertAllFaithful(reconstruction, recording)
    }
}
