package civictech.testkit.dst

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.durability.Journal
import civictech.cell.evolve.Effectful
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ============================================================================================
// The durable graph all three journal-plane suites are driven against.
// ============================================================================================

/**
 * A **durable, effectful** graph for the journal-plane faults: one `Effectful` sink on a
 * journaling host, fed by a source cell that lives outside the host, with the traffic issued
 * one frame per controller step.
 *
 * ## Why this shape and not `SelfTestGraphs.crossTalk`
 *
 * `crossTalk` appends hand-made byte arrays to a journal, which is enough to exercise the
 * *seam* and nothing about durability: its records are not kernel records, so no recovery
 * decodes them, no `RECORD_FRONTIER` is ever written, and `CorruptAt` could not produce a
 * `RecoveryIncomplete` because nothing was ever going to succeed. Every claim in
 * [CHA1-19]..[CHA1-22] is about what `HostDurability.recoverFrom` does with *real* records, so
 * the graph has to write real ones.
 *
 * The shape is `EffectfulRecoveryTest`'s (kernel), for the reason that test gives: the world the
 * sink acts on ([effects]) is **external to any cell instance**, so a double-fire after a
 * restart is observable — a crashed instance is discarded entirely, and only an effect target
 * outside the instance lifecycle can tell "acted twice" from "acted once, on a new instance".
 * `:testkit` has no KSP processor, so the cells are hand-written `Cell`s and the inlet is
 * reached through a dynamic `HostedCellProxy`, exactly as the kernel test does it.
 *
 * ## Two things a caller must know
 *
 * **State is per *build*, not per instance.** [effects] and [restarts] are cleared when the
 * builder runs, so one `DstRun` sees clean state; a sweep that runs `R + 1` runs against one
 * instance leaves only the *last* run's state behind. Per-run assertions come from each run's
 * `DstReport`, or from a [DstCheck] — which is handed the live world and runs before the next
 * build wipes anything.
 *
 * **The restart is inside the traffic window on purpose.** A fault's only clock is
 * [Fault.onStep], fired before a step the run must actually *reach*; a restart scheduled after
 * the graph quiesces never fires and the report marks it inert ([CHA1-24]). So traffic runs from
 * [FIRST_EMIT] for [emits] steps and a restart belongs in [restartStep], two steps before the
 * last emission — which also means the frames after the restart exercise the rebuilt host.
 */
internal class DurableEffectGraph(
    val id: String,
    val emits: Int = 8,
) {
    companion object {
        /**
         * The first step that emits, and it **must** be 0.
         *
         * `DstRun`'s loop ends the moment `controller.step()` finds no work, and the graph build
         * leaves none queued: `managementInlet.call.spawn` serves synchronously rather than
         * through the scheduler, so a graph that waits until step 3 to emit quiesces at step 0
         * with an empty trace and every fault reported inert. Measured — the first cut of this
         * fixture used 3 and produced `steps=0 inert=[tear, restart]`.
         *
         * The general rule for a rig graph: the step hook is the *only* thing keeping the run
         * alive, so its first firing has to be step 0.
         */
        const val FIRST_EMIT = 0

        const val HOST = "durable"
        const val JOURNAL = "sink-journal"
        const val SINK = "sink"
    }

    /** The external effect target: every value the sink acted on, in order. Survives a restart. */
    val effects: MutableList<Int> = mutableListOf()

    /** How many times the host was rebuilt during the last build's run. */
    var restarts: Int = 0
        private set

    /** The last built generation's live journal view — the decorated one the host writes through. */
    var journalView: Journal? = null
        private set

    /** The sink's `CellRef`, seed-derived so a rerun on the same seed builds the same graph. */
    lateinit var sinkRef: CellRef
        private set

    /** The step a restart fault should fire at: inside the traffic window, near its end. */
    val restartStep: Int get() = FIRST_EMIT + emits - 2

    /** The step after which no more traffic is issued. */
    val lastEmitStep: Int get() = FIRST_EMIT + emits - 1

    fun spec(): GraphSpec = GraphSpec(id) { world -> build(world) }

    private fun build(world: DstWorld) {
        effects.clear()
        restarts = 0

        val rng = world.rng("durable-effect-graph")
        sinkRef = CellRef(UUID(rng.nextLong(), rng.nextLong()))
        val journal = world.journals.declare(JOURNAL)
        journalView = journal
        val source = SourceCell(CellRef(UUID(rng.nextLong(), rng.nextLong())))
        var link: PortRef? = null

        world.hosts.declare(HOST) { ctx ->
            restarts = ctx.generation
            val host = ManagedHost(
                scheduler = ctx.scheduler,
                registry = ctx.registry,
                journalFor = { journal },
            )
            host.managementInlet.call.spawn(NotifierCell(sinkRef, effects))
            // Rewire the source at every generation: the pre-crash proxy routes to a host whose
            // scheduler has been shut down, so without this the traffic after a restart is lost
            // and every post-restart assertion would be vacuous.
            link?.let { source.outlet.unsubscribe(it) }
            val port = PortRef.generate()
            val proxy = HostedCellProxy.create(sinkRef, host, NotifierProxy::class.java) as NotifierProxy
            source.outlet.subscribe(Use.fixed(proxy.inlet.call, port))
            link = port
            host
        }

        world.cells.declare(SINK, sinkRef)

        world.steps.onStep { _, step ->
            if (step in FIRST_EMIT..lastEmitStep) source.emit(step - FIRST_EMIT)
        }
    }

    /** The source half: outside the host, so it survives the host's crash. */
    class SourceCell(override val ref: CellRef) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Int>>())
        fun emit(n: Int) = outlet.call.provide(n)
    }

    /** The effect boundary: every `provide` appends to a list outside any cell instance. */
    class NotifierCell(override val ref: CellRef, private val acted: MutableList<Int>) : Cell, Effectful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(
                object : Consumer<Int> {
                    override fun provide(input: Int) {
                        acted += input
                    }
                },
            )
        }
    }

    interface NotifierProxy {
        val inlet: Use<Consumer<Int>>
    }
}

// ============================================================================================

/**
 * [JournalFault]'s contract: [CHA1-19] (all six mutations, as index-level surgery on the opaque
 * `ByteArray` record list), [CHA1-20] (`RecoveryIncomplete`'s `recordIndex`/`total` captured and
 * reported), BS-9 and the [CHA1-62]/[CHA1-63] pair for the journal class (a torn tail converges;
 * a corrupted interior record provably does not), and [CHA1-24] (a mutation that changed nothing
 * is reported inert rather than passing quietly).
 *
 * The graph is [DurableEffectGraph] — see its KDoc for why a synthetic byte-array journal would
 * have proved none of this.
 */
class JournalFaultTest {

    // -----------------------------------------------------------------------------------------
    // The tag byte: the one thing this package reads out of an otherwise opaque record
    // -----------------------------------------------------------------------------------------

    /**
     * Pins [JournalRecords]' duplicated tag constants against what the kernel actually writes.
     *
     * `RECORD_FRAME`..`RECORD_BASELINE` are `private const` in `HostDurability`, and the item
     * this suite implements forbids widening kernel visibility — so the values are duplicated
     * in [JournalRecords] and this test is what stops the duplication from rotting. A kernel
     * renumbering fails **here**, loudly, instead of silently turning
     * [JournalRecords.indicesOfTag] into a filter that matches nothing and
     * [FrontierRollbackJournal] into a no-op that reports success.
     */
    @Test
    fun `journal record tags match what the kernel actually writes`() {
        val graph = DurableEffectGraph("journal-tag-census")
        val census = journalRecordCount(graph.spec(), seed = 11L, journal = DurableEffectGraph.JOURNAL)

        assertTrue(
            census.allTagsKnown,
            "the kernel wrote a record tag JournalRecords does not know: ${census.tags.keys} " +
                "vs known ${JournalRecords.KNOWN} — HostDurability's RECORD_* constants have moved " +
                "and JournalRecords must follow ($census)",
        )
        assertTrue(census.frameRecords > 0, "expected journaled invocation frames, got $census")
        assertTrue(
            census.frontierAdvances > 0,
            "expected RECORD_FRONTIER advances from the Effectful sink — without them every " +
                "[CHA1-22] assertion in FrontierRollbackTest is vacuous ($census)",
        )
    }

    // -----------------------------------------------------------------------------------------
    // [CHA1-19]: all six mutations, on the opaque record list
    // -----------------------------------------------------------------------------------------

    /**
     * All six mutations of [CHA1-19], as pure transforms of a `List<ByteArray>`.
     *
     * Asserted directly on the mutation values rather than through a run, because that is what
     * they *are*: the whole design claim of [JournalSurgery] is that journal-plane faults need
     * no record decoding, and a test that could only observe them through a recovery would not
     * distinguish "the surgery is wrong" from "the recovery reacted differently".
     */
    @Test
    fun `all six mutations are index-level surgery on the opaque record list`() {
        val records = (0..4).map { byteArrayOf(it.toByte(), 0x77) }
        fun apply(m: JournalMutation) = m.onReplay(records).map { it[0].toInt() }

        assertEquals(listOf(0, 1, 2), apply(JournalMutation.TruncateTail(2)), "TruncateTail")
        assertEquals(listOf(2, 3, 4), apply(JournalMutation.TruncatePrefix(2)), "TruncatePrefix")
        assertEquals(
            listOf(0, 1, JournalRecords.UNKNOWN.toInt(), 3, 4),
            apply(JournalMutation.CorruptAt(2)),
            "CorruptAt replaces in place, keeping the record count",
        )
        assertEquals(listOf(0, 1, 2, 2, 3, 4), apply(JournalMutation.DuplicateAt(2)), "DuplicateAt")
        assertEquals(listOf(4, 1, 2, 3, 0), apply(JournalMutation.ReorderAt(0, 4)), "ReorderAt swaps")

        // FailAppendAfter is the one mutation on the write path, so it has nothing to say on replay.
        val failing = JournalMutation.FailAppendAfter(2)
        assertEquals(records.size, failing.onReplay(records).size, "FailAppendAfter leaves replay alone")
        assertTrue(failing.acceptAppend(0) && failing.acceptAppend(1), "the first two appends are accepted")
        assertTrue(!failing.acceptAppend(2) && !failing.acceptAppend(9), "every append after n is refused")

        // Out of range is a no-op, never a clamp: the report marks the fault inert ([CHA1-24])
        // instead of silently corrupting some *other* record than the plan named.
        assertEquals(records.size, JournalMutation.CorruptAt(99).onReplay(records).size)
        assertEquals(records.size, JournalMutation.DuplicateAt(99).onReplay(records).size)
        assertEquals(records.size, JournalMutation.ReorderAt(0, 99).onReplay(records).size)
        assertEquals(0, JournalMutation.TruncateTail(99).onReplay(records).size, "over-truncation empties, never negative")
    }

    /**
     * The decorator contract the format-version refusal added (computenet-437w), and the reason
     * it is worth a test of its own: [Journal.formatVersion] carries an interface **default**, so
     * a decorator that forgets to forward it compiles and then silently reports the build's
     * version for a delegate pinned to another one — suppressing exactly the
     * `JournalFormatMismatch` the header exists to raise.
     */
    @Test
    fun `journal decorators forward their delegate's format version`() {
        val pinned = object : Journal {
            override val formatVersion: Int = 7
            override fun append(record: ByteArray) = Unit
            override fun replay(): List<ByteArray> = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
            override fun reset(records: List<ByteArray>) = Unit
        }

        assertEquals(7, MutatingJournal(pinned, JournalMutation.TruncateTail(1)).formatVersion)
        assertEquals(7, PrefixJournal(pinned, 2).formatVersion)
        assertEquals(7, FrontierRollbackJournal(pinned, 0).formatVersion)
    }

    // -----------------------------------------------------------------------------------------
    // BS-9 / [CHA1-20] / [CHA1-62]-[CHA1-63]: the converging and diverging controls
    // -----------------------------------------------------------------------------------------

    /**
     * BS-9's **converging** control ([CHA1-62]): a torn trailing record is a suffix the host
     * never acknowledged, so replay produces a clean prefix — every record before the tear
     * applied, recovery reported complete, and no dead letter.
     */
    @Test
    fun `BS-9 converging control - a torn tail replays clean minus exactly the torn record`() {
        val graph = DurableEffectGraph("journal-torn-tail")
        val restart = RestartAtFrontierFault("restart", DurableEffectGraph.HOST, DurableEffectGraph.JOURNAL, graph.restartStep)
        val tear = JournalFault.truncateTail("tear", DurableEffectGraph.JOURNAL, n = 1)

        val report = DstRun(graph.spec(), FaultPlan.of(3L, tear, restart)).execute()

        val recovery = assertNotNull(restart.lastRecovery, "the restart never fired: ${report.summary()}")
        assertTrue(recovery.complete, "a torn tail must replay clean, got $recovery")
        assertEquals(0, recovery.unapplied, "no record may be left unapplied by a torn tail")
        assertEquals(
            (restart.recordsAtRestart ?: 0) - 1,
            recovery.offered,
            "exactly the torn record is missing, no more",
        )
        assertTrue(
            report.deadLetters.isEmpty(),
            "a torn tail is not an error and must not dead-letter: ${report.deadLetters.map { it.description }}",
        )
        assertTrue(
            report.appliedFaults.none { it.inert },
            "both faults must have fired: ${report.appliedFaults}",
        )
    }

    /**
     * BS-9's **diverging** control ([CHA1-63]) and [CHA1-20]: a corrupted *interior* record
     * raises `RecoveryIncomplete(recordIndex == i, total == R)`, the partial replay is not
     * treated as complete, and the index and total reach the run report through the trace.
     */
    @Test
    fun `BS-9 diverging control - CorruptAt raises RecoveryIncomplete at that index and the replay is not complete`() {
        val graph = DurableEffectGraph("journal-corrupt-interior")
        val census = journalRecordCount(graph.spec(), seed = 5L, journal = DurableEffectGraph.JOURNAL)
        assertTrue(census.records >= 4, "need a journal with an interior to corrupt, got $census")

        // Corrupt a record the restart will actually reach: the restart fires inside the traffic
        // window, so the log is shorter then than the census's end-of-run count.
        val corruptAt = 1
        val restart = RestartAtFrontierFault("restart", DurableEffectGraph.HOST, DurableEffectGraph.JOURNAL, graph.restartStep)
        val rot = JournalFault.corruptAt("rot", DurableEffectGraph.JOURNAL, corruptAt)

        val report = DstRun(graph.spec(), FaultPlan.of(5L, rot, restart)).execute()

        val recovery = assertNotNull(restart.lastRecovery, "the restart never fired: ${report.summary()}")
        val incomplete = assertNotNull(
            recovery.incomplete,
            "a corrupted interior record must abort recovery, got $recovery",
        )
        assertEquals(corruptAt, incomplete.recordIndex, "recovery must abort AT the corrupted record")
        assertEquals(recovery.offered, incomplete.total, "total is the record count recovery was offered")
        assertTrue(!recovery.complete, "a partial replay must not be reported complete")
        assertTrue(recovery.unapplied > 0, "records from the corrupted one onward did not apply")

        // [CHA1-20]: the index and total are IN the report, via the trace the report carries.
        val traced = report.trace.filter { it.faultTag == "restart" }.mapNotNull { it.port }
        assertTrue(
            traced.any { it == "recovery-incomplete@${incomplete.recordIndex}/${incomplete.total}" },
            "the report must carry RecoveryIncomplete's index and total; traced: $traced",
        )

        // The bad record is dead-lettered before the throw — HostDurability's own accounting.
        assertTrue(
            report.deadLetters.any { it.description.contains("journal replay: record $corruptAt") },
            "the corrupted record must be dead-lettered: ${report.deadLetters.map { it.description }}",
        )
    }

    // -----------------------------------------------------------------------------------------
    // The write path, and inertness
    // -----------------------------------------------------------------------------------------

    /**
     * [JournalMutation.FailAppendAfter] on a live durable host — and **what it found**.
     *
     * ## A journal write failure is not contained; it aborts the run
     *
     * This test asserts the behaviour it *measured*, which is not the behaviour the fault was
     * designed expecting. `HostDurability`'s write-ahead append happens on the caller's own
     * dispatch path (`enqueueHostedInvocation`), inside the scheduler task
     * `SimulationController.step()` is running. There is no try/catch between the `journal.append`
     * and that step, so [JournalAppendFailed] propagates out of `controller.step()`, out of
     * `DstRun.execute()`'s driving loop, and out of the run — **before any `DstReport` is
     * built**. A full disk is therefore not a fault the graph survives and reports on; it is a
     * fault that destroys the experiment observing it.
     *
     * That is recorded rather than papered over, and it is why [PrefixRestartEntry] keeps a
     * throwable and a failing report in separate fields: a `FailAppendAfter` seed inside a sweep
     * lands in `error`, not in `report`.
     *
     * Whether the kernel *should* contain a journal write failure — dead-letter it, suspend the
     * cell, refuse the invocation — is a question about `[24-DUR-*]`, not about this rig, and this
     * suite renders no verdict on it. What the rig owes is that the fault fires and that its
     * effect is visible instead of silent, which is what is asserted below.
     */
    @Test
    fun `FailAppendAfter refuses writes on the live host, and the refusal aborts the run uncontained`() {
        val graph = DurableEffectGraph("journal-full-disk")
        val fault = JournalFault.failAppendAfter("full-disk", DurableEffectGraph.JOURNAL, n = 2)

        val failure = assertFailsWith<JournalAppendFailed> {
            DstRun(graph.spec(), FaultPlan.of(7L, fault)).execute()
        }

        assertEquals(DurableEffectGraph.JOURNAL, failure.journal)
        assertEquals(2, failure.appendsAccepted, "exactly n appends are accepted before the disk fills")
    }

    /** The refusal itself, isolated from any host, so its message and count are pinned. */
    @Test
    fun `a refused append names the journal and how many records it had accepted`() {
        val journal = civictech.cell.durability.InMemoryJournal()
        val mutating = MutatingJournal(journal, JournalMutation.FailAppendAfter(2), name = "j")

        mutating.append(byteArrayOf(1))
        mutating.append(byteArrayOf(2))
        val failure = assertFailsWith<JournalAppendFailed> { mutating.append(byteArrayOf(3)) }

        assertEquals("j", failure.journal)
        assertEquals(2, failure.appendsAccepted)
        assertEquals(2, journal.replay().size, "the refused record must not reach the delegate")
    }

    /**
     * [CHA1-24] / BS-13 for the journal class: a mutation whose index is past the end of the
     * journal changed nothing, and the report says **inert** rather than letting the run read as
     * a passing adversarial one. `CorruptAt(9999)` against a 20-record log is the shape of a plan
     * whose author mis-modelled the graph, and it must not be indistinguishable from a survived
     * corruption.
     */
    @Test
    fun `a mutation that changed nothing is reported inert, not passed`() {
        val graph = DurableEffectGraph("journal-inert-mutation")
        val restart = RestartAtFrontierFault("restart", DurableEffectGraph.HOST, DurableEffectGraph.JOURNAL, graph.restartStep)
        val impossible = JournalFault.corruptAt("out-of-range", DurableEffectGraph.JOURNAL, index = 9_999)

        val report = DstRun(graph.spec(), FaultPlan.of(13L, impossible, restart)).execute()

        assertEquals(
            listOf("out-of-range"),
            report.inertFaults.map { it.id },
            "the out-of-range mutation must be the only inert fault: ${report.appliedFaults}",
        )
        assertNull(
            restart.lastRecovery?.incomplete,
            "an out-of-range corruption must not damage the recovery it never touched",
        )
    }

    /**
     * The rig's own claim about the journal seam, checked: a decoration installed **mid-run**
     * takes effect on the journal the host is already holding, and removing it heals.
     *
     * That is [DstWorld.journals]' per-call resolution, and it is what makes a windowed journal
     * fault possible at all — without it a mutation could only be installed before the graph was
     * built, which is not a fault, it is a different graph.
     */
    @Test
    fun `a journal decoration installed mid-run takes effect and heals`() {
        val journals = Journals()
        val view = journals.declare("j")
        view.append(byteArrayOf(1))
        view.append(byteArrayOf(2))
        view.append(byteArrayOf(3))

        assertEquals(3, view.replay().size, "undecorated")
        val handle = journals.decorate("j") { MutatingJournal(it, JournalMutation.TruncateTail(2), "j") }
        assertEquals(1, view.replay().size, "the SAME view object now sees the mutation")
        assertTrue(journals.decorated("j"))
        handle.close()
        assertEquals(3, view.replay().size, "closing the handle heals the journal")
        assertTrue(!journals.decorated("j"))
    }
}
