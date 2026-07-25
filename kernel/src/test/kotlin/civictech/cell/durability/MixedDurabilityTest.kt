package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedCellProxy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * CP-C1 (per-cell journal tee): durability is a **per-cell** concern, not a
 * per-host one. `ManagedHost` takes a `journalFor(cellRef)` selector; the
 * host-wide journal is the degenerate case where the selector returns the same
 * journal for every cell. A cell whose selector returns `null` is volatile:
 * never journaled, never replayed.
 *
 * One host hosts a journaled cell and a volatile cell side by side. After a
 * crash + replay only the journaled cell's state comes back; the volatile cell
 * is re-delivered NOTHING (no double-delivery). The control run swaps the
 * per-cell selector for a whole-host constant selector journaling BOTH — and
 * BOTH come back, proving the volatile cell's loss in the mixed run was the
 * per-cell scoping doing its job, not the recovery mechanism failing.
 */
class MixedDurabilityTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private fun ops(host: ManagedHost, ref: CellRef): SetOps<String> =
        (HostedCellProxy.create(ref, host, SetInletProxy::class.java) as SetInletProxy).inlet.call

    /**
     * A non-`Stateful`, non-`Effectful` cell: it holds no snapshot and populates
     * no processed-frontier, so its state exists ONLY as the replayable frames
     * in the journal. Frame replay is its entire recovery path — exactly the
     * kind of contributor a checkpoint must not truncate away.
     */
    class TallyCell(override val ref: CellRef) : Cell {
        val received = mutableListOf<Int>()
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    received += input
                }
            })
        }
    }

    interface TallyProxy {
        val inlet: Use<Consumer<Int>>
    }

    private fun tally(host: ManagedHost, ref: CellRef): Consumer<Int> =
        (HostedCellProxy.create(ref, host, TallyProxy::class.java) as TallyProxy).inlet.call

    /**
     * PN-0b: `checkpoint` snapshots only `Stateful` cells then unconditionally
     * `journal.reset(...)`. A journal serving ONLY a non-`Stateful` cell has an
     * empty snapshot AND an empty processed-frontier — the reset would truncate
     * the frames that are the cell's ONLY recovery, silently destroying its
     * state. The host must refuse the checkpoint and leave the WAL intact so a
     * later replay still rebuilds the cell.
     */
    @Test
    fun `checkpoint refuses a journal whose only contributor is non-Stateful, leaving the WAL replayable`() {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal() // "the disk": the only thing that survives the crash
        val tallyRef = CellRef(UUID.randomUUID())

        var host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(TallyCell(tallyRef))
        controller.runToIdle()

        // drive the non-Stateful cell: every call reaches the WAL as a frame,
        // and frame replay is the cell's ONLY route back after a crash
        tally(host, tallyRef).provide(10)
        tally(host, tallyRef).provide(20)
        controller.runToIdle()

        // checkpoint would snapshot the (empty) Stateful set then reset() the
        // WAL down to it — destroying frames 10 and 20. It must refuse instead.
        shouldThrow<IllegalArgumentException> { host.checkpoint(journal) }

        // CRASH: only the journal survives. Because checkpoint refused, the
        // frames are still there and replay rebuilds the cell exactly.
        host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val recovered = TallyCell(tallyRef)
        host.managementInlet.call.spawn(recovered)
        controller.runToIdle()
        host.recoverFrom(journal)
        controller.runToIdle()

        recovered.received shouldBe listOf(10, 20)
    }

    @Test
    fun `per-cell tee recovers the journaled cell and re-delivers nothing to the volatile one`() {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal() // "the disk": the only thing that survives the crash

        val journaledRef = CellRef(UUID.randomUUID())
        val volatileRef = CellRef(UUID.randomUUID())

        // per-cell selector: the journaled cell tees to disk; the volatile one never does
        val selector: (CellRef) -> Journal? = { if (it == journaledRef) journal else null }

        var host = ManagedHost(scheduler = controller.scheduler(), journalFor = selector)
        host.managementInlet.call.spawn(SetCell<String>(journaledRef))
        host.managementInlet.call.spawn(SetCell<String>(volatileRef))
        controller.runToIdle()

        // both cells accept identical traffic; only the journaled one reaches the WAL
        ops(host, journaledRef).add("apple")
        ops(host, journaledRef).add("banana")
        ops(host, volatileRef).add("fig")
        ops(host, volatileRef).add("grape")
        controller.runToIdle()

        // CRASH: host, cells, queues, links discarded — only the journal object survives
        host = ManagedHost(scheduler = controller.scheduler(), journalFor = selector)
        val recoveredJournaled = SetCell<String>(journaledRef)
        val recoveredVolatile = SetCell<String>(volatileRef)
        host.managementInlet.call.spawn(recoveredJournaled)
        host.managementInlet.call.spawn(recoveredVolatile)
        controller.runToIdle()
        host.recoverFrom(journal) // replays only what the journal holds — the journaled cell's frames
        controller.runToIdle()

        // the journaled cell's state is restored exactly...
        recoveredJournaled.membership() shouldBe setOf("apple", "banana")
        // ...and NOTHING was re-delivered to the volatile cell: it was never journaled, so
        // replay cannot double-deliver to it — it comes back empty.
        recoveredVolatile.membership() shouldBe emptySet()
    }

    @Test
    fun `control - a whole-host constant selector restores BOTH cells`() {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal()

        val cellA = CellRef(UUID.randomUUID())
        val cellB = CellRef(UUID.randomUUID())

        // the degenerate whole-host case: one constant selector journals EVERY cell
        val selector: (CellRef) -> Journal? = { journal }

        var host = ManagedHost(scheduler = controller.scheduler(), journalFor = selector)
        host.managementInlet.call.spawn(SetCell<String>(cellA))
        host.managementInlet.call.spawn(SetCell<String>(cellB))
        controller.runToIdle()

        // identical traffic to the mixed run — only the selector differs
        ops(host, cellA).add("apple")
        ops(host, cellA).add("banana")
        ops(host, cellB).add("fig")
        ops(host, cellB).add("grape")
        controller.runToIdle()

        host = ManagedHost(scheduler = controller.scheduler(), journalFor = selector)
        val recoveredA = SetCell<String>(cellA)
        val recoveredB = SetCell<String>(cellB)
        host.managementInlet.call.spawn(recoveredA)
        host.managementInlet.call.spawn(recoveredB)
        controller.runToIdle()
        host.recoverFrom(journal)
        controller.runToIdle()

        // BOTH restored: the volatile cell's loss in the mixed run was the per-cell
        // scoping excluding it, not the mechanism failing to replay.
        recoveredA.membership() shouldBe setOf("apple", "banana")
        recoveredB.membership() shouldBe setOf("fig", "grape")
    }

    @Test
    fun `default - the single-journal ctor and a constant selector produce a byte-identical WAL`() {
        val ref = CellRef(UUID.randomUUID())

        fun drive(host: ManagedHost, controller: SimulationController) {
            host.managementInlet.call.spawn(SetCell<String>(ref))
            controller.runToIdle()
            ops(host, ref).add("apple")
            ops(host, ref).add("banana")
            ops(host, ref).remove("apple")
            controller.runToIdle()
        }

        val c1 = SimulationController(seed = 1)
        val single = InMemoryJournal()
        drive(ManagedHost(scheduler = c1.scheduler(), journal = single), c1)

        val c2 = SimulationController(seed = 1)
        val selected = InMemoryJournal()
        drive(ManagedHost(scheduler = c2.scheduler(), journalFor = { selected }), c2)

        // the host-wide journal is exactly the constant-selector degenerate case
        val a = single.replay()
        val b = selected.replay()
        a.size shouldBe b.size
        a.zip(b).forEach { (x, y) -> x.toList() shouldBe y.toList() }
    }
}
