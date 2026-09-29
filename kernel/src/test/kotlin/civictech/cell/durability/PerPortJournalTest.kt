package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.JournalRecords
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.wire.WireCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/**
 * computenet-xy7w4.3 (feature computenet-xy7w4 rule R-E, design xy7w4-D4): the per-cell
 * journal selector (CP-C1, `journalFor(cellRef)`) can now be given **per port**,
 * `journalForPort(cellRef, portName)` — one cell may journal an externally-driven inlet
 * while leaving a sibling inlet, fed only by another journaled cell's cascade, volatile
 * (replaying the upstream cell re-derives it). The per-cell forms remain the degenerate
 * case (every port maps alike), and every journaled port of one cell must still name the
 * SAME [Journal] instance — a cell's `Stateful` snapshot, outlet-wave state and durable
 * epoch are captured once per cell, not once per port.
 */
class PerPortJournalTest {

    /**
     * A small `Stateful` fold with two inlets: `input` (driven directly by an external
     * caller) and `derived` (a [Propagate] sink subscribed to another cell's outlet).
     * Realizes R-E's "cell C with inlets input and derived".
     */
    private class FoldCell(override val ref: CellRef) : Cell, Stateful {
        val input = registerPort("input", FanInlet.create<Consumer<String>>())
        val derived = registerPort("derived", FanInlet.create<Propagate<SetDelta<String>>>())

        private val elements = mutableSetOf<String>()
        fun membership(): Set<String> = elements.toSet()

        init {
            input.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    elements += input
                }
            })
            derived.serve(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) {
                    elements += value.adds.keys
                }
            })
        }

        override fun snapshot(): Serializable = HashSet(elements)

        @Suppress("UNCHECKED_CAST")
        override fun restore(state: Serializable) {
            elements.clear()
            elements += state as Set<String>
        }
    }

    private interface FoldInputProxy {
        val input: Use<Consumer<String>>
    }

    private interface FoldDerivedProxy {
        val derived: Use<Propagate<SetDelta<String>>>
    }

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private fun foldInput(host: ManagedHost, ref: CellRef): Consumer<String> =
        (HostedCellProxy.create(ref, host, FoldInputProxy::class.java) as FoldInputProxy).input.call

    private fun foldDerived(host: ManagedHost, ref: CellRef): Propagate<SetDelta<String>> =
        (HostedCellProxy.create(ref, host, FoldDerivedProxy::class.java) as FoldDerivedProxy).derived.call

    private fun setOps(host: ManagedHost, ref: CellRef): SetOps<String> =
        (HostedCellProxy.create(ref, host, SetInletProxy::class.java) as SetInletProxy).inlet.call

    private fun decodedFrames(journal: Journal) =
        journal.replay()
            .map { JournalRecords.decode(it) }
            .filterIsInstance<DecodedJournalRecord.Frame>()
            .map { WireCodec.decode(it.payload) }

    /**
     * R-E: `journalForPort` journals C's `input` and U's own inlet, never C's `derived`.
     * Live traffic proves the tee; a crash + recovery proves C's full 6-element fold comes
     * back — the 3 `derived` ones re-derived by U's replay, never re-delivered from a
     * `derived` frame (there is none).
     *
     * NON-VACUITY (per the ticket, recorded in the bead comment too): keying the intake tee
     * on the cell instead of the target port — `portJournalSelector(cellRef, cellRef.let {
     * "input" })`-style collapse, i.e. always consulting `input`'s answer for every port of
     * C — makes `derived` frames land in the journal and the assertion below red.
     */
    @Test
    fun `R-E per-port journal holds only journaled ports, recovery re-derives the rest`() {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal()

        val cRef = CellRef(UUID.randomUUID())
        val uRef = CellRef(UUID.randomUUID())

        val selector: (CellRef, String) -> Journal? = { ref, port ->
            when {
                ref == cRef && port == "input" -> journal
                ref == uRef -> journal
                else -> null
            }
        }

        var host = ManagedHost(scheduler = controller.scheduler(), journalForPort = selector)
        var c = FoldCell(cRef)
        var u = SetCell<String>(uRef)
        host.managementInlet.call.spawn(c)
        host.managementInlet.call.spawn(u)
        controller.runToIdle()
        u.outlet.subscribe(Use.fixed(foldDerived(host, cRef), PortRef.generate()))
        controller.runToIdle()

        listOf("a1", "a2", "a3").forEach { foldInput(host, cRef).provide(it) }
        controller.runToIdle()
        listOf("b1", "b2", "b3").forEach { setOps(host, uRef).add(it) }
        controller.runToIdle()

        c.membership() shouldBe setOf("a1", "a2", "a3", "b1", "b2", "b3")

        val frames = decodedFrames(journal)
        // only C's "input" and U's own inlet ever reach the journal
        frames.count { it.cellRef == cRef && it.portName == "input" } shouldBe 3
        frames.count { it.cellRef == cRef && it.portName == "derived" } shouldBe 0
        frames.count { it.cellRef == uRef } shouldBe 3

        // CRASH: host, cells, links discarded — only the journal survives
        host = ManagedHost(scheduler = controller.scheduler(), journalForPort = selector)
        c = FoldCell(cRef)
        u = SetCell<String>(uRef)
        host.managementInlet.call.spawn(c)
        host.managementInlet.call.spawn(u)
        controller.runToIdle()
        // re-wire BEFORE recovery so U's replayed re-emissions cascade into `derived`
        u.outlet.subscribe(Use.fixed(foldDerived(host, cRef), PortRef.generate()))
        controller.runToIdle()

        host.recoverFrom(journal)
        controller.runToIdle()

        c.membership() shouldBe setOf("a1", "a2", "a3", "b1", "b2", "b3")
    }

    /**
     * D4's refusal, disagreement half: `input` maps to J1 and `derived` maps to J2 — two
     * journaled ports of one cell naming different journals has no coherent meaning (a
     * `Stateful` snapshot is captured once per cell), so `spawn` refuses, naming the cell
     * and both port names.
     */
    @Test
    fun `refusal - two journaled ports of one cell naming different journals`() {
        val controller = SimulationController(seed = 2)
        val j1 = InMemoryJournal()
        val j2 = InMemoryJournal()
        val cRef = CellRef(UUID.randomUUID())

        val selector: (CellRef, String) -> Journal? = { ref, port ->
            when {
                ref == cRef && port == "input" -> j1
                ref == cRef && port == "derived" -> j2
                else -> null
            }
        }
        val host = ManagedHost(scheduler = controller.scheduler(), journalForPort = selector)

        val ex = shouldThrow<IllegalArgumentException> {
            host.managementInlet.call.spawn(FoldCell(cRef))
        }
        ex.message shouldContain cRef.toString()
        ex.message shouldContain "input"
        ex.message shouldContain "derived"
    }

    /**
     * D4's refusal, outlet half: a selector naming a journal for an OUTLET port is a
     * configuration error — outlet-side journaling of spontaneous emissions is undecided
     * (spec 90 roadmap I-7 §8, this feature's D6 non-goal).
     */
    @Test
    fun `refusal - a selector naming a journal for an outlet port`() {
        val controller = SimulationController(seed = 3)
        val journal = InMemoryJournal()
        val uRef = CellRef(UUID.randomUUID())
        val selector: (CellRef, String) -> Journal? = { ref, port ->
            if (ref == uRef && port == "outlet") journal else null
        }
        val host = ManagedHost(scheduler = controller.scheduler(), journalForPort = selector)

        val ex = shouldThrow<IllegalArgumentException> {
            host.managementInlet.call.spawn(SetCell<String>(uRef))
        }
        ex.message shouldContain "outlet"
    }

    /** `journalFor` and `journalForPort` are mutually exclusive (an `init` refusal). */
    @Test
    fun `refusal - journalFor and journalForPort passed together`() {
        shouldThrow<IllegalArgumentException> {
            ManagedHost(journalFor = { null }, journalForPort = { _, _ -> null })
        }
    }

    /**
     * The per-cell forms are the degenerate case of the per-port selector (D4): a
     * `journalForPort` that ignores its port argument produces a byte-identical WAL to
     * the equivalent `journalFor`, for the same traffic under the same seed (mirrors
     * `MixedDurabilityTest`'s "byte-identical WAL" shape).
     */
    @Test
    fun `degenerate - journalForPort ignoring the port is byte-identical to journalFor`() {
        val ref = CellRef(UUID.randomUUID())

        fun drive(host: ManagedHost, controller: SimulationController) {
            host.managementInlet.call.spawn(SetCell<String>(ref))
            controller.runToIdle()
            setOps(host, ref).add("apple")
            setOps(host, ref).add("banana")
            setOps(host, ref).remove("apple")
            controller.runToIdle()
        }

        val c1 = SimulationController(seed = 1)
        val viaJournalFor = InMemoryJournal()
        drive(ManagedHost(scheduler = c1.scheduler(), journalFor = { viaJournalFor }), c1)

        val c2 = SimulationController(seed = 1)
        val viaJournalForPort = InMemoryJournal()
        drive(ManagedHost(scheduler = c2.scheduler(), journalForPort = { _, _ -> viaJournalForPort }), c2)

        val a = viaJournalFor.replay()
        val b = viaJournalForPort.replay()
        a.size shouldBe b.size
        a.zip(b).forEach { (x, y) -> x.toList() shouldBe y.toList() }
    }
}
