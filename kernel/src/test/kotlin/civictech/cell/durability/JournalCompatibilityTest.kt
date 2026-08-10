package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.evolve.Effectful
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.OutletWaveState
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.wire.WireCodec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

/**
 * KFX feature 4, BS-43 (`[KFX-21]`; spec `[24-DUR-02]` in `20/24`; AGENTS.md
 * *"preserve binary/wire compatibility … prefer additive encoding"*) — **the
 * identity change is additive on disk**.
 *
 * The `[KFX-12]` change (`OutletWaveState.durable` + `HostDurability`'s
 * `RECORD_OUTLET_WAVE`) was *built* to be additive: a separate record type
 * rather than a widened `CheckpointRecord`, so a journal written before it
 * simply lacks the record and every pre-existing checkpoint blob still
 * deserializes. That is an argument in a KDoc; this file is the evidence. Two
 * arms, deliberately independent:
 *
 * - the **fixture arm** replays a real journal produced by the pre-change
 *   kernel, checked in as a binary resource;
 * - the **absent-toleration arm** strips `RECORD_OUTLET_WAVE` out of a journal
 *   written by the *current* kernel, so the guard keeps working even if the
 *   fixture ever has to be retired.
 *
 * ## The fixture, and how to regenerate it
 *
 * `kernel/src/test/resources/civictech/cell/durability/prechange-journal.bin`
 * was generated from **pre-change `main`, commit `0126b8d`** — the last commit
 * before the identity change landed as `34892d9` (PR #15). Regeneration, without
 * touching this repo's git lifecycle:
 *
 * ```
 * git archive 0126b8d | tar -x -C <scratch>
 * # drop a one-off JUnit test into <scratch>/kernel/src/test/kotlin/civictech/cell/
 * # durability/ that reproduces [NotifierCell], the two proxy interfaces and [World]
 * # below, then runs the sequence below against `FileJournal(File(tmpDir, "j.bin"))`
 * # and prints that file's path.
 * (cd <scratch> && ./gradlew :kernel:test --tests '<that test>')
 * cp <the printed path> kernel/src/test/resources/civictech/cell/durability/prechange-journal.bin
 * ```
 *
 * Two things a future regenerator needs and cannot infer from the copy:
 *
 * - **Drop [World.derivedEpoch] from the copied [World].** It calls
 *   [OutletWaveState.durable], which is *this* change — it does not exist at
 *   `0126b8d` and the copy will not compile with it. Nothing else in [World] is
 *   post-change.
 * - **The fixture is not byte-reproducible.** The pre-change kernel minted its
 *   emission epoch with `randomUUID`, so every regeneration carries a different
 *   `sourceId`, and Java serialization of the checkpoint's maps is not
 *   order-stable either. The acceptance test for a regenerated fixture is
 *   therefore the *structural* assertions in the fixture arm below — record
 *   types `[2, 1, 1, 3]`, no type 4, and a carried wave identity that is a
 *   random v4 rather than the ref-derived epoch — not a diff against this file.
 *
 * The generator is deliberately *not* committed: it only compiles against the
 * pre-change tree, and a copy in this tree would rot silently.
 *
 * **The graph** is [World] below, reproduced there verbatim: a journaled
 * [SetCell] source feeding an [Effectful] sink through the host intake (the
 * `DUR-SRCID` shape; `EffectfulRecoveryTest`/`OutletWaveRecoveryTest` are the
 * precedents), both cells on one durable host over the whole-host degenerate
 * journal tee.
 *
 * **The fixed refs** are [SOURCE_REF] and [SINK_REF] — literal UUIDs rather than
 * `randomUUID`, because every replay-stable identity in play derives from them:
 * `PortRef.of(cellRef, name)`, `SetCell.tagSource`, and
 * [OutletWaveState.durable]. A different ref is a different graph and the
 * fixture would not apply to it.
 *
 * **The sequence** (`SimulationController(seed = 43)`):
 *
 * 1. spawn sink, spawn source, subscribe the source's outlet to the sink's
 *    inlet through a `HostedCellProxy`, run to idle;
 * 2. `add("apple")`, `add("banana")`, run to idle — the sink has now fired for
 *    both, so its processed-frontier is non-empty;
 * 3. `host.checkpoint(journal)` — compaction: apple's and banana's frames leave
 *    the WAL, and `RECORD_CHECKPOINT` (state + frontier) is all that is left of
 *    them;
 * 4. `add("cherry")`, run to idle — the tail: a `RECORD_FRAME` for the source's
 *    inlet, a `RECORD_FRAME` for the sink's inlet, and a `RECORD_FRONTIER` for
 *    the sink's frontier advance.
 *
 * That yields exactly four records — `[2, 1, 1, 3]`, asserted below — covering
 * all three pre-change record types and **no** type 4. Expected recovered
 * membership: `{apple, banana, cherry}`.
 *
 * ## The honest boundary
 *
 * BS-43's claim is *same recovered state* plus *absent-toleration*. It is **not**
 * an effect-once claim on a pre-change journal, and this file does not make one.
 * A pre-change journal's `RECORD_CHECKPOINT`/`RECORD_FRONTIER` records carry the
 * **random** `sourceId`s the pre-change kernel minted per incarnation, so the
 * frontier they restore cannot match the ref-derived identity the changed
 * kernel's replayed re-emissions carry — and those re-emissions are therefore
 * not suppressed. The pre-change double-fire is *reproduced* for pre-change
 * journals, not silently fixed; it is asserted head-on in the fixture arm so
 * the boundary is legible from the test rather than only from this comment. It
 * is exactly what the identity change buys for journals written *after* it
 * (`OutletWaveRecoveryTest`), and nothing it can retroactively buy for journals
 * written before.
 *
 * Concretely, `effects == ["cherry"]` after recovery is **one** fire out of two
 * candidates, and both halves matter:
 *
 * - the journaled *sink* frame (record 2, carrying the pre-change random
 *   `sourceId` at counter 3) **is** suppressed — the restored pre-change
 *   frontier still keys on that same random id, so old frontier data written
 *   before the change is honoured unchanged. That is the compatibility half;
 * - the *source*'s replay of record 1 re-drives the `SetCell` and it **re-emits**
 *   under the ref-derived epoch at counter 1, which no pre-change frontier entry
 *   can match. That is the un-suppressed fire, and it is the boundary half —
 *   observably the behaviour the pre-change kernel itself had here, where the
 *   recovered outlet minted a fresh random epoch and was likewise unmatched.
 */
class JournalCompatibilityTest {

    companion object {
        /** The fixture's source cell — fixed, because every derived identity keys off it. */
        val SOURCE_REF = CellRef(UUID.fromString("00000000-0000-4000-8000-0000000000a1"))

        /** The fixture's `Effectful` sink cell — likewise fixed. */
        val SINK_REF = CellRef(UUID.fromString("00000000-0000-4000-8000-0000000000a2"))

        const val FIXTURE = "/civictech/cell/durability/prechange-journal.bin"

        /**
         * `RECORD_OUTLET_WAVE`. The constant is `private` to
         * `kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt:25`, so the
         * literal is repeated here rather than exported for a test; the other three
         * are `RECORD_FRAME = 1`, `RECORD_CHECKPOINT = 2`, `RECORD_FRONTIER = 3`
         * (same file, `:22-24`).
         */
        const val RECORD_OUTLET_WAVE: Byte = 4
    }

    /** The effect boundary: every delta acts on [world], which outlives any instance. */
    class NotifierCell(override val ref: CellRef, private val world: MutableList<String>) : Cell, Effectful {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.onEach { delta -> world += delta.adds.keys.sorted() }
        }
    }

    interface NotifierProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    /**
     * One incarnation of the fixture graph — the *same* construction the
     * (uncommitted) generator ran against the pre-change tree, which is what
     * makes the checked-in journal applicable to it. A "crash" is building a
     * fresh [World] over the same journal and the same [CellRef]s.
     */
    private class World(
        controller: SimulationController,
        journal: Journal,
        effects: MutableList<String>,
    ) {
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val source = SetCell<String>(SOURCE_REF)
        val sink = NotifierCell(SINK_REF, effects)

        init {
            host.managementInlet.call.spawn(sink)
            host.managementInlet.call.spawn(source)
            val sinkCall = (HostedCellProxy.create(SINK_REF, host, NotifierProxy::class.java)
                as NotifierProxy).inlet.call
            source.outlet.subscribe(Use.fixed(sinkCall, PortRef.generate()))
        }

        fun ops(): SetOps<String> =
            (HostedCellProxy.create(SOURCE_REF, host, SetInletProxy::class.java) as SetInletProxy).inlet.call

        /** The ref-derived epoch this outlet sits on when nothing overrides it. */
        fun derivedEpoch(): UUID = OutletWaveState.durable(source.outlet.ref).sourceId
    }

    /** The fixture, copied out of the jar so [FileJournal] can read it as an ordinary file. */
    private fun fixtureFile(dir: File): File {
        val file = File(dir, "prechange-journal.bin")
        checkNotNull(javaClass.getResourceAsStream(FIXTURE)) { "missing fixture resource $FIXTURE" }
            .use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    /**
     * **The fixture arm** — `[KFX-21]`/BS-43's headline: a journal written by the
     * pre-change kernel replays, under the changed kernel, to the state it always
     * did, with the new record type absent-tolerated because it is simply not there.
     */
    @Test
    fun `a pre-change journal replays to the same recovered state under the changed kernel`(@TempDir dir: File) {
        val journal = FileJournal(fixtureFile(dir))

        // the fixture really is pre-change: the three pre-change record types, in the
        // order the documented sequence produces them, and no RECORD_OUTLET_WAVE at all
        val records = journal.replay()
        records.map { it[0].toInt() } shouldBe listOf(2, 1, 1, 3)
        records.none { it[0] == RECORD_OUTLET_WAVE }.shouldBeTrue()

        val controller = SimulationController(seed = 43)
        val effects = mutableListOf<String>()
        val world = World(controller, journal, effects)
        controller.runToIdle()

        // PROVENANCE, checked rather than taken on the commit hash in this class's KDoc:
        // the fixture was written by the pre-change kernel, not merely stripped of its
        // type-4 records. The wave identity its one mid-graph frame carries is a RANDOM
        // (version 4) UUID — what the pre-change kernel minted per incarnation — and is
        // NOT the ref-derived epoch installDurableEpochs would have put that outlet on.
        // A "fixture" regenerated from the current kernel would fail here.
        val carried = records
            .filter { it[0].toInt() == 1 }
            .mapNotNull { WireCodec.decode(it.copyOfRange(1, it.size)).invocation.context?.timestamp?.sourceId }
        carried shouldHaveSize 1
        carried.single().version() shouldBe 4
        carried.single() shouldNotBe world.derivedEpoch()

        // recovery COMPLETES: a RecoveryIncomplete (or any decode failure behind it)
        // would surface right here — the pre-change RECORD_CHECKPOINT blob still
        // deserializes because CheckpointRecord was deliberately not widened
        world.host.recoverFrom(journal)
        controller.runToIdle()

        // same recovered state as the pre-change kernel reached: the checkpointed
        // prefix restored from the blob, the tail rebuilt by frame replay
        world.source.membership() shouldBe setOf("apple", "banana", "cherry")

        // and, with no RECORD_OUTLET_WAVE to adopt, the recovered outlet sits on the
        // ref-derived durable epoch installDurableEpochs put it on at spawn — the
        // absent record is tolerated, not required
        world.source.outlet.waveState().sourceId shouldBe world.derivedEpoch()

        // THE BOUNDARY, asserted rather than argued away (see this class's KDoc):
        // the fixture's frontier records key the RANDOM sourceIds the pre-change
        // kernel minted, so they cannot suppress a re-emission carrying the
        // ref-derived identity. "cherry" — which the pre-change run had already
        // acted on before the crash — fires a SECOND time here. That is the
        // pre-change double-fire faithfully reproduced for a pre-change journal;
        // BS-43 claims same recovered STATE, never effect-once on this fixture.
        // (For journals written AFTER the change the same shape IS effect-once —
        // OutletWaveRecoveryTest.)
        effects shouldBe listOf("cherry")

        // the recovered graph is live, not merely restored: post-recovery traffic
        // reaches the sink and the source's state advances
        world.ops().add("date")
        controller.runToIdle()
        world.source.membership() shouldBe setOf("apple", "banana", "cherry", "date")
        effects shouldBe listOf("cherry", "date")
    }

    /**
     * **The absent-toleration arm** — the always-runnable half of `[KFX-21]`,
     * independent of the checked-in fixture: build a journal with the CURRENT
     * kernel (checkpoint included, so `RECORD_OUTLET_WAVE` is genuinely present),
     * strip every one of those records, and recover from both. The recovered state
     * must be identical — the record is an optimisation recovery may consume, never
     * a record recovery may require.
     */
    @Test
    fun `a journal stripped of every RECORD_OUTLET_WAVE recovers to the same state as the unstripped one`() {
        val controller = SimulationController(seed = 44)
        val written = InMemoryJournal()
        val live = World(controller, written, mutableListOf())
        controller.runToIdle()

        live.ops().add("apple")
        live.ops().add("banana")
        controller.runToIdle()
        live.host.checkpoint(written) // writes RECORD_OUTLET_WAVE beside the checkpoint blob
        live.ops().add("cherry")
        controller.runToIdle()
        live.source.membership() shouldBe setOf("apple", "banana", "cherry")

        val all = written.replay()
        // the arm is only meaningful if the record is actually there to strip
        all.any { it[0] == RECORD_OUTLET_WAVE }.shouldBeTrue()

        val unstripped = InMemoryJournal().also { it.reset(all) }
        val stripped = InMemoryJournal().also { j -> j.reset(all.filter { it[0] != RECORD_OUTLET_WAVE }) }
        stripped.replay().size shouldBe all.size - all.count { it[0] == RECORD_OUTLET_WAVE }

        fun recover(journal: Journal): World {
            val effects = mutableListOf<String>()
            val world = World(controller, journal, effects)
            controller.runToIdle()
            world.host.recoverFrom(journal) // must not throw RecoveryIncomplete on either
            controller.runToIdle()
            return world
        }

        val withRecord = recover(unstripped)
        val withoutRecord = recover(stripped)

        // the point of the arm: recovery SUCCEEDS without the record, and lands on
        // exactly the state the record-carrying recovery lands on
        withoutRecord.source.membership() shouldBe withRecord.source.membership()
        withoutRecord.source.membership() shouldBe setOf("apple", "banana", "cherry")

        // with the record, the outlet adopts the epoch recorded at checkpoint time;
        // without it, it falls back to the ref-derived durable epoch — the same
        // sourceId here (nothing rotated this outlet), which is exactly why the
        // fallback is safe for state. What the record adds is the COUNTER: replay
        // alone rewinds the outlet's high-water to the tail's own re-derivation,
        // which is why the record exists at all (see OutletWaveState.durable) — a
        // difference in emission counters, never in recovered state.
        withoutRecord.source.outlet.waveState().sourceId shouldBe withoutRecord.derivedEpoch()
        withRecord.source.outlet.waveState().sourceId shouldBe withRecord.derivedEpoch()
        (withRecord.source.outlet.waveState().highWater >
            withoutRecord.source.outlet.waveState().highWater).shouldBeTrue()
    }
}
