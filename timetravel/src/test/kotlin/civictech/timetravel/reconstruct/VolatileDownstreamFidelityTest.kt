package civictech.timetravel.reconstruct

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
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
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.journal.FrameRecord
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable

/**
 * computenet-ocv.1 (from yhvlz-D8's "downstream-of-volatile propagation deliberately not
 * modelled"): can a cell DOWNSTREAM of a volatile cell be reported `Faithful` while its
 * reconstructed state differs from the live run's? Settled by a run, per edge kind.
 *
 * Graph: `s0 (volatile), s1 -> union -> view`, all [SetCell]/[UnionSetCell] (replay-stable, so
 * `ReplayStable.classify` alone says `Faithful`). Live, the recording host's `journalFor` returns
 * `null` for `s0` (`[24-DUR-01]`), so nothing addressed to `s0` is journaled; `s1`, `union` and
 * `view` journal to one journal. The offline reconstruction is told the same thing through
 * `GraphSpecSource(journaled = { it != s0 })`.
 *
 * ## Finding (observed on darwin/arm64, a2ca73c4)
 *
 * - **Local `linkTo` edges (a `GraphSpec` `connect`): a mis-label IS possible.** A local link
 *   subscribes in-process and never reaches `ManagedHost.enqueueHostedInvocation` (the only
 *   journal-append site), so the journal holds `s1`'s frames only — nothing of `s0`'s adds reaches
 *   it on either side of the edge. Offline, `union` and `view` fold only `s1`'s contributions yet
 *   are reported `Faithful`: a plausible-but-unverified state presented as the run's past, which
 *   is what `[TTD1-34]` forbids. The only signal is the run-level verdict, which is
 *   `Unreconstructible(VOLATILE_CELL)` because `s0` itself is (`[TTD1-37]`) — it names the
 *   volatile cell, not the cells it starved.
 * - **Routed edges (the upstream outlet subscribes a `HostedCellProxy` of the downstream inlet):
 *   no mis-label.** Each downstream delivery goes through the host's intake and is journaled as
 *   the downstream cell's OWN frame (`journalFor(union)`, not `journalFor(s0)`), so the replay
 *   feeds `union` and `view` everything the live run delivered, and their `Faithful` is true.
 *
 * The local-edge test below PINS the current behaviour as a documented mis-label; it is not an
 * endorsement. Which verdict and reason those cells should carry instead (`[TTD1-34]` reads as
 * `Unreconstructible`, and no existing [Reason] describes "starved by a volatile upstream"), and
 * where the reconstructor would learn the edge topology (`GraphBuild` carries none), are open
 * decisions recorded on computenet-ocv.2 — change this test when they land.
 */
class VolatileDownstreamFidelityTest {

    /** The union/view inlet shape, routed through a host (`FrontierCutReconstructionTest`'s shape). */
    interface DeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    private val script = listOf(0 to "a1", 1 to "b1", 0 to "a2", 1 to "b2")

    private class Recording(
        val journal: InMemoryJournal,
        val spec: GraphSpec,
        val refs: DurableGraphFixture.Refs,
        val live: Map<CellRef, Serializable>,
    ) {
        val volatile: CellRef get() = refs.sources[0]
    }

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

    /**
     * Records the graph live with `s0` volatile. [routed] = false links with `GraphSpec`
     * `connect`s (local, in-process); true spawns only and routes every edge through the host.
     */
    private fun record(seed: Long, routed: Boolean): Recording {
        val refs = DurableGraphFixture.Refs(seed, 2)
        val volatile = refs.sources[0]
        val journal = InMemoryJournal()
        val controller = SimulationController(seed)
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            registry = LocationRegistry(),
            journal = journal,
            journalFor = { ref -> if (ref == volatile) null else journal },
        )
        lateinit var cells: List<Cell>
        val spec = graph(host.managementInlet) {
            val sources = refs.sources.mapIndexed { i, ref ->
                spawn("s$i", IdentityBinding.Exact(ref)) { r -> SetCell<String>(r) }
            }
            val union = spawn("union", IdentityBinding.Exact(refs.union)) { r -> UnionSetCell<String>(r) }
            val view = spawn("view", IdentityBinding.Exact(refs.view)) { r -> UnionSetCell<String>(r) }
            if (!routed) {
                sources.forEach { connect(it, "outlet", union, "inlet") }
                connect(union, "outlet", view, "inlet")
            }
            cells = sources.map { it.cell } + union.cell + view.cell
        }
        controller.runToIdle()
        if (routed) routeAll(host, refs, cells)

        val proxies = refs.sources.map { ref ->
            (HostedCellProxy.create(ref, host, DurableGraphFixture.SetInletProxy::class.java)
                as DurableGraphFixture.SetInletProxy).inlet.call
        }
        script.forEach { (sourceIndex, element) ->
            proxies[sourceIndex].add(element)
            controller.runToIdle()
        }
        return Recording(journal, spec, refs, DurableGraphFixture.snapshotsOf(cells))
    }

    private fun reconstruct(recording: Recording, routed: Boolean): Pair<Reconstruction, RunTimeline> {
        val reading = JournalReader.open(JournalSource.InMemory(recording.journal, "j"))
        val timeline = RunTimeline.of(reading).getValue("j")
        val source = GraphSource { host ->
            GraphSpecSource(recording.spec, journaled = { it != recording.volatile }).build(host)
                .also { if (routed) routeAll(host, recording.refs, it.cells) }
        }
        return Reconstructor(reading, timeline, source).stateAt(Position.Index(timeline.size - 1)) to timeline
    }

    /** A `UnionSetCell` snapshot is the member -> tags map itself. */
    private fun membership(snapshot: Serializable): Set<String> {
        val members = snapshot.shouldBeInstanceOf<Map<*, *>>()
        return members.keys.mapTo(mutableSetOf()) { it as String }
    }

    private fun reconstructed(reconstruction: Reconstruction, ref: CellRef) =
        reconstruction.cells.getValue(ref).shouldBeInstanceOf<CellReconstruction.Reconstructed>()

    /** Which cells the journal holds frames for — the evidence, observed rather than assumed. */
    private fun framedRefs(timeline: RunTimeline): Set<CellRef> =
        timeline.positions.mapNotNullTo(mutableSetOf()) { (it.record as? FrameRecord)?.cellRef }

    private fun assertVolatileNamed(reconstruction: Reconstruction, recording: Recording) {
        reconstruction.cells.getValue(recording.volatile).shouldBeInstanceOf<CellReconstruction.Unreconstructible>()
            .fidelity shouldBe Fidelity.Unreconstructible(setOf(Reason.VOLATILE_CELL))
        reconstruction.run.shouldBeInstanceOf<Fidelity.Unreconstructible>().reasons shouldContain Reason.VOLATILE_CELL
    }

    @Test
    fun `local linkTo edges - DOCUMENTED MIS-LABEL - union and view are Faithful though they lack the volatile source's adds`() {
        val recording = record(seed = 61, routed = false)
        val (reconstruction, timeline) = reconstruct(recording, routed = false)
        val refs = recording.refs

        // Evidence: nothing downstream is journaled on a local edge, and the volatile source is
        // not journaled at all — the journal holds s1's frames and nothing else.
        framedRefs(timeline) shouldBe setOf(refs.sources[1])
        timeline.size shouldBe 2

        // The live run delivered s0's adds downstream.
        val liveMembers = setOf("a1", "b1", "a2", "b2")
        membership(recording.live.getValue(refs.union)) shouldBe liveMembers
        membership(recording.live.getValue(refs.view)) shouldBe liveMembers

        assertVolatileNamed(reconstruction, recording)

        // s1 is reconstructed faithfully and correctly: its own frames are all it ever had.
        val s1 = reconstructed(reconstruction, refs.sources[1])
        s1.snapshot shouldBe recording.live.getValue(refs.sources[1])
        s1.fidelity shouldBe Fidelity.Faithful

        // THE MIS-LABEL: union and view reconstruct without s0's contributions — their state
        // differs from the live run's — and are still reported Faithful ([TTD1-34] violated;
        // pinned as current behaviour, see the class KDoc).
        for (ref in listOf(refs.union, refs.view)) {
            val cell = reconstructed(reconstruction, ref)
            membership(cell.snapshot) shouldBe setOf("b1", "b2")
            cell.snapshot shouldNotBe recording.live.getValue(ref)
            cell.fidelity shouldBe Fidelity.Faithful
        }
    }

    @Test
    fun `routed edges - no mis-label - downstream frames are journaled as the downstream's own, so Faithful is true`() {
        val recording = record(seed = 62, routed = true)
        val (reconstruction, timeline) = reconstruct(recording, routed = true)
        val refs = recording.refs

        // Evidence: s0 has no frame, but union and view journal every delivery — s0's included —
        // as their own (waved) frames.
        framedRefs(timeline) shouldBe setOf(refs.sources[1], refs.union, refs.view)
        val waved = timeline.positions.filter { it.wave != null }
            .mapTo(mutableSetOf()) { (it.record as FrameRecord).cellRef }
        waved shouldBe setOf(refs.union, refs.view)

        assertVolatileNamed(reconstruction, recording)

        val liveMembers = setOf("a1", "b1", "a2", "b2")
        for (ref in listOf(refs.union, refs.view)) {
            membership(recording.live.getValue(ref)) shouldBe liveMembers
            val cell = reconstructed(reconstruction, ref)
            membership(cell.snapshot) shouldBe liveMembers
            cell.snapshot shouldBe recording.live.getValue(ref) // tags too, not only members
            cell.fidelity shouldBe Fidelity.Faithful
        }
        val s1 = reconstructed(reconstruction, refs.sources[1])
        s1.snapshot shouldBe recording.live.getValue(refs.sources[1])
        s1.fidelity shouldBe Fidelity.Faithful
    }
}
