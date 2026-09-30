package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.io.path.createTempDirectory

/**
 * computenet-s4n8y: [civictech.cell.host.HostDurability.checkpoint] selects the cells (and
 * ports) to snapshot with `=== journal` — reference identity against the exact selector
 * target the host was constructed with, not path or content equality. A caller that passes a
 * FRESH [FileJournal] instance for the SAME underlying file the host actually journals to
 * builds an object that is `===`-unequal to every selector's target: no cell and no port is
 * "bound" to it.
 *
 * Before this fix, `checkpoint` treated that as the same degenerate, harmless case as "a
 * journal truly serving nothing" — the pre-existing recoverable-content guard's `cells.keys
 * .none { cellJournalSelector(it) === journal }` disjunct is trivially true when NO cell is
 * bound, so the guard passed, and `checkpoint` proceeded to `journal.reset(...)` with an
 * all-but-empty checkpoint blob, silently truncating every frame already on that file. This is
 * exactly the computenet-q5jzk.3 report: "agora DurabilityTest: restart 3 recovered 0.5 instead
 * of 0.369375" — a checkpoint that discarded frames nobody's snapshot had captured.
 *
 * The fix fails closed, but only where there is something to fail closed ABOUT: when no cell
 * or port selector resolves `===` to the journal, `checkpoint` now reads the journal's own
 * existing content and refuses (naming the mismatch) only if that content is non-empty —
 * because `PerPortJournalTest`'s pre-existing degenerate-case test legitimately calls
 * `checkpoint(journal)` on a journal that is unbound (every current cell answers `journalFor`
 * with `null`) but has never had anything written to it, and expects that to succeed as a
 * no-op. An unbound-and-empty journal is genuinely nothing to lose; an unbound-but-populated
 * one — the fresh-`FileJournal`-for-the-same-path shape above — is exactly the data loss this
 * bead reports, and is refused. The tests below pin all three shapes: refuse (populated,
 * unbound), no-op (empty, unbound), and the pre-existing "bound but nothing recoverable"
 * guard (unaffected).
 */
class CheckpointJournalIdentityTest {

    /**
     * Non-`Stateful`, non-`Effectful`: it holds no snapshot and populates no
     * processed-frontier, so every one of its calls exists ONLY as a replayable frame in the
     * journal — the same shape [MixedDurabilityTest]'s `TallyCell` uses to make truncation
     * observable rather than coincidentally harmless.
     */
    private class TallyCell(override val ref: CellRef) : Cell {
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

    private interface TallyProxy {
        val inlet: Use<Consumer<Int>>
    }

    private fun tally(host: ManagedHost, ref: CellRef): Consumer<Int> =
        (HostedCellProxy.create(ref, host, TallyProxy::class.java) as TallyProxy).inlet.call

    /**
     * The headline case: a caller checkpoints with a distinct [FileJournal] instance opened
     * on the exact same path the host was constructed with. It is refused, by name, before any
     * frame is touched — the on-disk frames survive intact and a later recovery from the
     * ACTUAL journal instance still rebuilds the cell.
     */
    @Test
    fun `checkpoint refuses a fresh journal instance for the same file the host was built with`() {
        val dir = createTempDirectory("checkpoint-journal-identity").toFile()
        val file = dir.resolve("host.journal")
        val journal = FileJournal(file) // the instance the host is actually wired to
        val controller = SimulationController(seed = 1)
        val ref = CellRef(UUID.randomUUID())

        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(TallyCell(ref))
        controller.runToIdle()

        // frames land on the WAL; frame replay is this cell's ONLY recovery
        tally(host, ref).provide(10)
        tally(host, ref).provide(20)
        controller.runToIdle()

        val recordsBefore = journal.replay()
        recordsBefore.size shouldBe 2

        // the caller's mistake: a NEW FileJournal for the same path, not the instance the
        // host was constructed with — `===`-unequal to every selector's target
        val impostor = FileJournal(file)

        val thrown = shouldThrow<IllegalArgumentException> { host.checkpoint(impostor) }
        thrown.message!!.contains("no cell or port") shouldBe true

        // refused BEFORE any reset: the frames already on disk are untouched
        journal.replay() shouldBe recordsBefore

        // and a crash + recovery from the REAL journal still rebuilds the cell
        val recovered = TallyCell(ref)
        val host2 = ManagedHost(scheduler = controller.scheduler(), journal = FileJournal(file))
        host2.managementInlet.call.spawn(recovered)
        controller.runToIdle()
        host2.recoverFrom(FileJournal(file))
        controller.runToIdle()
        recovered.received shouldBe listOf(10, 20)
    }

    /**
     * A journal that serves no cell at all — not even by coincidence of a stale reference —
     * is refused the same way: this pins that the guard is about binding, not about the
     * particular reason a journal ends up unbound.
     */
    @Test
    fun `checkpoint refuses an unbound journal that already holds records on disk`() {
        val dir = createTempDirectory("checkpoint-journal-identity-orphan").toFile()
        val hostJournal = FileJournal(dir.resolve("host.journal"))
        val orphanFile = dir.resolve("orphan.journal")
        // pre-seed the orphan file with a record from OUTSIDE this host entirely — e.g. a
        // leftover from a previous run, or another cell's journal on a shared path — so it
        // has real content to lose, unlike a brand-new empty journal.
        FileJournal(orphanFile).append("pre-existing".toByteArray())
        val orphan = FileJournal(orphanFile)
        val controller = SimulationController(seed = 2)
        val ref = CellRef(UUID.randomUUID())

        val host = ManagedHost(scheduler = controller.scheduler(), journal = hostJournal)
        host.managementInlet.call.spawn(TallyCell(ref))
        controller.runToIdle()
        tally(host, ref).provide(1)
        controller.runToIdle()

        val thrown = shouldThrow<IllegalArgumentException> { host.checkpoint(orphan) }
        thrown.message!!.contains("already holds") shouldBe true
        FileJournal(orphanFile).replay().map { String(it) } shouldBe listOf("pre-existing")
    }

    /**
     * The nuance the naive "always refuse an unbound journal" version of this fix got
     * wrong, caught by `PerPortJournalTest`'s pre-existing degenerate-case test: a whole-host
     * `journal` combined with a per-cell `journalFor` that answers null for every current
     * cell is legitimately unbound too, but nothing was ever written to it, so there is
     * nothing to lose. `checkpoint` must treat that as the documented safe no-op the bead's
     * acceptance criteria allow — not throw — while still refusing an unbound journal that
     * has real content (the case above).
     */
    @Test
    fun `checkpoint on a genuinely empty, unbound journal is a safe no-op, not a throw`() {
        val dir = createTempDirectory("checkpoint-journal-identity-empty-noop").toFile()
        val journal = FileJournal(dir.resolve("host.journal"))
        val vRef = CellRef(UUID.randomUUID())
        // every cell answers null: `journal` is passed to the host but bound to nothing
        val selector: (CellRef) -> Journal? = { null }
        val controller = SimulationController(seed = 4)

        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal, journalFor = selector)
        host.managementInlet.call.spawn(TallyCell(vRef))
        controller.runToIdle()
        tally(host, vRef).provide(99)
        controller.runToIdle()
        journal.replay().size shouldBe 0 // never written: `vRef` is volatile under `selector`

        host.checkpoint(journal) // must not throw
        journal.replay().size shouldBe 0 // and must not have written anything either
    }

    /**
     * Control: checkpointing with the ACTUAL instance the host was constructed with is
     * unaffected by this fix — the binding check passes, and the pre-existing
     * recoverable-content guard (a DIFFERENT clause, for a bound-but-nothing-to-snapshot
     * journal) still fires exactly as before.
     */
    @Test
    fun `checkpoint with the actual bound journal instance is unaffected`() {
        val dir = createTempDirectory("checkpoint-journal-identity-bound").toFile()
        val journal = FileJournal(dir.resolve("host.journal"))
        val controller = SimulationController(seed = 3)
        val ref = CellRef(UUID.randomUUID())

        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(TallyCell(ref))
        controller.runToIdle()
        tally(host, ref).provide(10)
        controller.runToIdle()

        // bound, but the only contributor is non-Stateful with no processed-frontier: the
        // OTHER guard (recoverable content) refuses this, same as MixedDurabilityTest.
        shouldThrow<IllegalArgumentException> { host.checkpoint(journal) }
        journal.replay().size shouldBe 1 // refused: frame still intact
    }
}
