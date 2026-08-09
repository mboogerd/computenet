package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
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

    /**
     * KFX BS-13 / `[KFX-07]`, the **`Effectful` arm** of per-journal scoping
     * (`[24-DUR-03]`: "a journal SHALL only ever hold its own cells' records, such
     * that replaying it restores exactly those cells and re-delivers nothing to a
     * co-hosted volatile cell").
     *
     * The sibling test above proves the *state* half with two `SetCell`s. This one
     * proves the half that actually costs something when it is wrong: the co-hosted
     * cell is an `Effectful` sink whose every delivery acts on a world OUTSIDE the
     * instance lifecycle ([EffectfulRecoveryTest.NotifierCell]'s `world`), so a
     * foreign record leaking into chain A's journal does not merely restore a bit of
     * state twice — it re-fires an effect the pre-crash instance already performed,
     * and no downstream idempotence takes that back.
     *
     * Two independent chains on ONE host: chain A is a `SetCell` teeing to
     * `journalA`; chain B is an (unhosted, volatile) source feeding an `Effectful`
     * sink teeing to `journalB`. The source is unhosted deliberately — it is the
     * producing outlet that mints the `(sourceId, counter)` the sink's
     * processed-frontier keys on, and it survives the crash exactly as
     * `EffectfulRecoveryTest`'s does, so post-recovery traffic continues the same
     * source lane rather than opening a new one.
     *
     * Then: recover `journalA` ONLY. Chain A's state comes back; the co-hosted
     * `Effectful` sink receives nothing and `world` does not grow. Recovering
     * `journalB` afterwards replays chain B's own frames, which its own restored
     * frontier suppresses as already-acted (`[24-DUR-05]`), so the effect count is
     * still exactly one per logical delta — and live traffic after both recoveries
     * still reaches the sink, so the scoping is not achieved by breaking delivery.
     *
     * This lives in the kernel rather than the `dur` corpus because the corpus cannot
     * express it: `KernelDriverDur` holds ONE shared journal, so "two journals on one
     * host, replay only one" has no scenario vocabulary. The additive descriptor that
     * would give it one is filed as beads `computenet-elc` (a gated schema change
     * under `concord/schema`), deliberately not invented here.
     *
     * NON-VACUITY. The "received nothing" assertion is not free, but it is also not
     * discriminated by the obvious perturbation: the frontier records ride the SAME
     * per-cell selector as the frames, so leaking *everything* into one journal leaks
     * the suppressing frontier along with the frames and stays green. The perturbation
     * that isolates `[24-DUR-03]` is therefore on the frame tee alone — make
     * `ManagedHost`'s two intake WAL writes
     * (`journalSelector(hostedInvocation.cellRef)?.append(hostDurability.journalFrame(...))`)
     * resolve to the first non-null journal they ever saw, while
     * `advanceAndJournalFrontier` keeps the honest per-cell selector. `journalA` then
     * holds chain B's frames but not chain B's frontier, and `recoverFrom(journalA)`
     * re-drives the sink: this test then fails with `world` = `[1, 2, 1, 2]` where it
     * expects `[1, 2]`. The unperturbed kernel passes.
     */
    @Test
    fun `replaying one journal re-delivers nothing to a co-hosted Effectful sink on another journal`() {
        val controller = SimulationController(seed = 1)
        val journalA = InMemoryJournal() // chain A's "disk"
        val journalB = InMemoryJournal() // chain B's "disk" — a DIFFERENT journal on the same host
        val world = mutableListOf<Int>() // the external effect target, outside any cell instance

        val setRefA = CellRef(UUID.randomUUID())
        val sinkRefB = CellRef(UUID.randomUUID())

        // per-cell selector: each chain tees to its own journal, nothing else is journaled
        val selector: (CellRef) -> Journal? = {
            when (it) {
                setRefA -> journalA
                sinkRefB -> journalB
                else -> null
            }
        }

        var host = ManagedHost(scheduler = controller.scheduler(), journalFor = selector)
        host.managementInlet.call.spawn(SetCell<String>(setRefA))
        host.managementInlet.call.spawn(EffectfulRecoveryTest.NotifierCell(sinkRefB, world))
        controller.runToIdle()

        // chain B's producing outlet: unhosted, so it survives the crash and keeps
        // minting the same source lane the sink's frontier is keyed on
        val source = EffectfulRecoveryTest.SourceCell()
        var link: PortRef? = null
        fun rewire(target: ManagedHost) {
            link?.let { source.outlet.unsubscribe(it) }
            val portRef = PortRef.generate()
            val sinkInlet = (
                HostedCellProxy.create(sinkRefB, target, EffectfulRecoveryTest.NotifierProxy::class.java)
                    as EffectfulRecoveryTest.NotifierProxy
                ).inlet.call
            source.outlet.subscribe(Use.fixed(sinkInlet, portRef))
            link = portRef
        }
        rewire(host)
        controller.runToIdle()

        // pre-crash traffic on both chains; chain B's effects have ALREADY landed on the world
        ops(host, setRefA).add("apple")
        ops(host, setRefA).add("banana")
        source.emit(1)
        source.emit(2)
        controller.runToIdle()
        world shouldBe listOf(1, 2)

        // CRASH: host, registry and every live instance vanish — both journals survive
        host = ManagedHost(scheduler = controller.scheduler(), journalFor = selector)
        val recoveredA = SetCell<String>(setRefA)
        host.managementInlet.call.spawn(recoveredA)
        host.managementInlet.call.spawn(EffectfulRecoveryTest.NotifierCell(sinkRefB, world))
        controller.runToIdle()

        // replay ONE journal: chain A's
        host.recoverFrom(journalA)
        controller.runToIdle()

        // chain A is back...
        recoveredA.membership() shouldBe setOf("apple", "banana")
        // ...and the co-hosted Effectful sink received NOTHING: chain A's journal holds
        // only chain A's records, so there is no frame there to re-drive the effect with.
        world shouldBe listOf(1, 2)

        // recovering chain B's OWN journal is what restores chain B — and it is still
        // exactly-once: its replayed frames sit at or behind its restored frontier.
        host.recoverFrom(journalB)
        controller.runToIdle()
        world shouldBe listOf(1, 2)

        // and the scoping did not achieve "nothing fired" by breaking delivery
        rewire(host)
        source.emit(3)
        controller.runToIdle()
        world shouldBe listOf(1, 2, 3)
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
