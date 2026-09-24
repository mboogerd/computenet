package civictech.timetravel.reconstruct

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.data.SetCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.durability.InMemoryJournal
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.graph
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.timetravel.fidelity.Fidelity
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/**
 * TTD1 F5 BS-8 (`computenet-yhvlz.3`), yhvlz-D8/D12: a cell the graph source names volatile
 * (`journaled(ref) == false`) is `Unreconstructible(VOLATILE_CELL)` and `snapshotOf` is never
 * called for it (`[TTD1-28]`); a non-`Stateful` cell stays `Unreconstructible(NOT_STATEFUL)`
 * (`[TTD1-25]`); the run verdict is `Unreconstructible` with both reasons (`[TTD1-37]`); and the
 * graph's own reconstructible cells (the two sources, the union, the view) stay `Faithful` —
 * downstream-of-volatile propagation is not modelled (yhvlz-D8's last paragraph): nothing here is
 * actually wired downstream of the volatile cell, so this test cannot and does not speak to that.
 *
 * The volatile cell is truly volatile, not merely labelled so for the classifier
 * (yhvlz-D12 "true volatility, not a lie to the classifier"): it is spawned on the *live*
 * recording host too, unlinked, under a `journalFor` selector that routes it to no journal at
 * all, so nothing was ever written for it to replay in the first place. The offline
 * reconstruction spawns a fresh instance of the same class at the same ref and separately tells
 * the classifier `journaled(ref) == false` — the control method below shows that classification,
 * not the (absent) journal content, is what keeps the probe un-snapshotted.
 *
 * AMENDS yhvlz-D12's proof lever: the design's throwing-`snapshot()` probe cannot be built at
 * all. `ManagedHost`'s spawn handler unconditionally runs `checkpoints[cell.ref] = cell.snapshot()`
 * for every `Stateful` cell (`ManagedHost.kt` "spawn-time checkpoint: what a RESTART supervision
 * restores", independent of the journal selector or the host being the live or the offline one) —
 * a cell whose `snapshot()` throws fails at `spawn`, not inside `Reconstructor.observe`, on
 * *either* host (falsified by running the throwing version: it errors out of `record()`'s own
 * `spawn` call, before any journal or reconstruction is involved). [VolatileProbe] instead counts
 * calls and always succeeds: the spawn-time checkpoint is call 1 on whichever host builds it: the
 * volatile branch (method 1) excludes the cell from `snapshotOf` entirely, so the *offline*
 * instance's count stays at 1; the control (method 2) does not, so it reaches 2. That is the same
 * fact yhvlz-D12 wanted (`snapshotOf` was or was not called), proven by a count instead of a throw.
 */
class UnreconstructibleIsNamedTest {

    /**
     * A `Stateful` cell that counts every `snapshot()` call instead of throwing (see the class
     * KDoc's AMENDS note): `ManagedHost` calls it once, unconditionally, the moment this cell is
     * spawned on any host. Whether [Reconstructor.observe] calls `snapshotOf` for it a *second*
     * time is exactly what distinguishes the volatile case from the control.
     */
    class VolatileProbe(override val ref: CellRef) : Cell, Stateful {
        var snapshotCalls: Int = 0

        override fun snapshot(): Serializable {
            snapshotCalls++
            return snapshotCalls
        }

        override fun restore(state: Serializable) {}
    }

    /** A cell with no state at all — the `[TTD1-25]` case (`ReconstructAtWaveTest.Opaque`'s shape). */
    class Opaque(override val ref: CellRef) : Cell

    private val script = listOf(0 to "a1", 1 to "b1", 0 to "a2")

    private class Recording(
        val journal: InMemoryJournal,
        val spec: GraphSpec,
        val refs: DurableGraphFixture.Refs,
        val probeRef: CellRef,
    )

    /**
     * `s0, s1 -> union -> view` (`DurableGraphFixture.record`'s shape — that fixture takes no
     * `journalFor`, so the graph is rebuilt here), plus an unlinked, live [VolatileProbe] spawned
     * directly on a host whose `journalFor` returns `null` for the probe's ref and the recording
     * journal for everything else: nothing is ever written to the journal for the probe, live.
     */
    private fun record(seed: Long): Recording {
        val refs = DurableGraphFixture.Refs(seed, 2)
        val probeRef = CellRef(UUID(seed, 999))
        val journal = InMemoryJournal()
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            registry = registry,
            journal = journal,
            journalFor = { ref -> if (ref == probeRef) null else journal },
        )

        lateinit var sourceCells: List<SetCell<String>>
        val spec = graph(host.managementInlet) {
            val sourceHandles = refs.sources.mapIndexed { i, ref ->
                spawn("s$i", IdentityBinding.Exact(ref)) { r -> SetCell<String>(r) }
            }
            val unionHandle = spawn("union", IdentityBinding.Exact(refs.union)) { r -> UnionSetCell<String>(r) }
            val viewHandle = spawn("view", IdentityBinding.Exact(refs.view)) { r -> UnionSetCell<String>(r) }
            sourceHandles.forEach { connect(it, "outlet", unionHandle, "inlet") }
            connect(unionHandle, "outlet", viewHandle, "inlet")
            sourceCells = sourceHandles.map { it.cell }
        }
        controller.runToIdle()

        host.managementInlet.call.spawn(VolatileProbe(probeRef))
        controller.runToIdle()

        val proxies = refs.sources.map { ref ->
            @Suppress("UNCHECKED_CAST")
            (HostedCellProxy.create(ref, host, DurableGraphFixture.SetInletProxy::class.java)
                as DurableGraphFixture.SetInletProxy).inlet.call
        }
        script.forEach { (sourceIndex, element) ->
            proxies[sourceIndex].add(element)
            controller.runToIdle()
        }
        check(sourceCells.isNotEmpty())

        return Recording(journal, spec, refs, probeRef)
    }

    /** A `SetCell` snapshot keeps its members under `"adds"` (`FrontierCutReconstructionTest.membership`'s shape). */
    private fun setMembership(cell: CellReconstruction): Set<String> {
        val reconstructed = cell.shouldBeInstanceOf<CellReconstruction.Reconstructed>()
        val map = reconstructed.snapshot.shouldBeInstanceOf<Map<*, *>>()
        @Suppress("UNCHECKED_CAST")
        val adds = map["adds"] as Map<String, *>
        return adds.keys
    }

    @Test
    fun `a volatile cell and a non-Stateful cell are Unreconstructible with no value, run is Unreconstructible, the graph stays Faithful`() {
        val recording = record(seed = 41)
        val opaqueRef = CellRef(UUID(41, 998))
        lateinit var offlineProbe: VolatileProbe
        val graphSource = GraphSource { host ->
            val base = GraphSpecSource(recording.spec, journaled = { it != recording.probeRef }).build(host)
            val probe = VolatileProbe(recording.probeRef)
            offlineProbe = probe
            val opaque = Opaque(opaqueRef)
            host.managementInlet.call.spawn(probe)
            host.managementInlet.call.spawn(opaque)
            GraphBuild(base.cells + probe + opaque, journaled = base.journaled)
        }

        val reading = JournalReader.open(JournalSource.InMemory(recording.journal, "j"))
        val timeline = RunTimeline.of(reading).getValue("j")
        val reconstructor = Reconstructor(reading, timeline, graphSource)

        val reconstruction = reconstructor.stateAt(Position.Index(timeline.size - 1))

        // The probe: Unreconstructible(VOLATILE_CELL), no value. snapshotCalls == 1 is exactly
        // the unconditional spawn-time checkpoint (ManagedHost); a second call would mean
        // Reconstructor.observe called snapshotOf for it despite journaled(ref) == false.
        val probeResult = reconstruction.cells.getValue(recording.probeRef)
            .shouldBeInstanceOf<CellReconstruction.Unreconstructible>()
        probeResult.cellClass shouldBe VolatileProbe::class.java.name
        probeResult.fidelity shouldBe Fidelity.Unreconstructible(setOf(Reason.VOLATILE_CELL))
        offlineProbe.snapshotCalls shouldBe 1

        // The opaque cell: Unreconstructible(NOT_STATEFUL), no value.
        val opaqueResult = reconstruction.cells.getValue(opaqueRef)
            .shouldBeInstanceOf<CellReconstruction.Unreconstructible>()
        opaqueResult.cellClass shouldBe Opaque::class.java.name
        opaqueResult.fidelity shouldBe Fidelity.Unreconstructible(setOf(Reason.NOT_STATEFUL))

        // The run verdict rolls both reasons up.
        val run = reconstruction.run.shouldBeInstanceOf<Fidelity.Unreconstructible>()
        run.reasons shouldContain Reason.VOLATILE_CELL
        run.reasons shouldContain Reason.NOT_STATEFUL

        // The graph's own cells stay Faithful, and s0's membership is exactly its scripted adds.
        for (ref in recording.refs.all) {
            reconstruction.cells.getValue(ref).shouldBeInstanceOf<CellReconstruction.Reconstructed>()
                .fidelity shouldBe Fidelity.Faithful
        }
        setMembership(reconstruction.cells.getValue(recording.refs.sources[0])) shouldBe setOf("a1", "a2")

        // Neither Unreconstructible entry carries a value: enforced by the sealed type itself
        // ([TTD1-34], reflection-checked by ReconstructionShapeTest) — the two shouldBeInstanceOf
        // casts above already prove it: CellReconstruction.Unreconstructible declares no
        // Serializable/CellStateView property at all.
    }

    @Test
    fun `control - when volatility is unknown the same probe IS reconstructed normally, snapshotted twice`() {
        val recording = record(seed = 42)
        lateinit var offlineProbe: VolatileProbe
        val graphSource = GraphSource { host ->
            val base = GraphSpecSource(recording.spec).build(host)
            val probe = VolatileProbe(recording.probeRef)
            offlineProbe = probe
            host.managementInlet.call.spawn(probe)
            GraphBuild(base.cells + probe, journaled = base.journaled)
        }

        val reading = JournalReader.open(JournalSource.InMemory(recording.journal, "j"))
        val timeline = RunTimeline.of(reading).getValue("j")
        val reconstructor = Reconstructor(reading, timeline, graphSource)

        val reconstruction = reconstructor.stateAt(Position.Index(timeline.size - 1))

        // journaled == null (unknown): the probe is not volatile, so it is classified and
        // reconstructed normally, and snapshotOf WAS called for it — the spawn-time checkpoint
        // (call 1) plus Reconstructor.observe's own snapshotOf (call 2).
        val probeResult = reconstruction.cells.getValue(recording.probeRef)
            .shouldBeInstanceOf<CellReconstruction.Reconstructed>()
        probeResult.cellClass shouldBe VolatileProbe::class.java.name
        probeResult.snapshot shouldBe 2
        offlineProbe.snapshotCalls shouldBe 2
    }
}
