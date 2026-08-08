package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.MessageContext
import civictech.cell.evolve.Effectful
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.OutletWaveState
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * KFX feature 2, task 1 — **replay-stable outlet wave identity across durable
 * recovery** (`[KFX-08]`–`[KFX-13]`; spec `[24-DUR-04]`/`[24-DUR-05]` in
 * `20/24`, `30/31` recovery precedence, 93 I-14 Rules S1/S5).
 *
 * `EffectfulRecoveryTest` proves the processed-frontier suppresses a *replayed
 * frame*, but only over a graph whose source is **volatile and outside the
 * host** — its emissions are never re-derived by recovery, so the frontier
 * always meets the identity it recorded. `concord/corpus/DISPUTES.md` records
 * the construction that breaks: a **journaled** source feeding an `Effectful`
 * sink. Recovery replays the source's own input frames, the rebuilt source
 * re-emits, and (before this change) that re-emission carried a freshly-minted
 * random `sourceId` the sink's restored frontier could not match — so the
 * effect fired a second time, violating `[24-DUR-04]` on the outlet wave plane
 * while the tag plane already satisfied it.
 *
 * The `[KFX-12]` decision this file asserts — **durable recovery is a
 * preserved-epoch continuation**, with a ref-derived `sourceId` and a
 * checkpoint-carried counter high-water — is argued in full against
 * `[24-DUR-04]`, `30/31` and 93 I-14 Rules S1/S5 on
 * [OutletWaveState.durable]'s KDoc.
 *
 * Every exactly-once assertion here is against the in-process [world] effect
 * log ([KFX-24], the `EffectfulRecoveryTest` shape) — never an end-to-end
 * external exactly-once claim; that ceiling is 93 I-7's and belongs to CON1.
 */
class OutletWaveRecoveryTest {

    /**
     * The journaled source: driven through the host intake (so its input frames
     * land in the WAL and are replayed on recovery) and re-emitting each delta
     * from its own [FanOutlet] — the emission whose wave identity must survive
     * the crash.
     */
    class RelayCell(override val ref: CellRef) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Int>>())
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    outlet.call.provide(input)
                }
            })
        }
    }

    /** The effect boundary: every `provide` acts on [world], which outlives any instance. */
    class NotifierCell(override val ref: CellRef, private val world: MutableList<Int>) : Cell, Effectful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    world += input
                }
            })
        }
    }

    interface RelayProxy {
        val inlet: Use<Consumer<Int>>
    }

    interface NotifierProxy {
        val inlet: Use<Consumer<Int>>
    }

    /**
     * One incarnation of the graph: source and sink co-hosted on a durable host
     * over [journal] — the whole-host degenerate tee, so both cells' frames and
     * the sink's frontier advances ride the same WAL. A "crash" is simply
     * building a fresh [World] over the same journal and the same [CellRef]s:
     * every live instance is discarded, only the journal survives.
     */
    private class World(
        controller: SimulationController,
        val journal: InMemoryJournal,
        relayRef: CellRef,
        notifierRef: CellRef,
        effects: MutableList<Int>,
    ) {
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val relay = RelayCell(relayRef)
        val notifier = NotifierCell(notifierRef, effects)

        init {
            host.managementInlet.call.spawn(notifier)
            host.managementInlet.call.spawn(relay)
            // source → sink through the host intake, so the sink's inlet sees a
            // journaled frame carrying the source outlet's MessageContext
            val sink = (HostedCellProxy.create(notifierRef, host, NotifierProxy::class.java)
                as NotifierProxy).inlet.call
            relay.outlet.subscribe(Use.fixed(sink, PortRef.generate()))
        }

        /** Drives the source from outside: a root frame, no wave context of its own. */
        fun feed(n: Int) {
            (HostedCellProxy.create(relay.ref, host, RelayProxy::class.java) as RelayProxy)
                .inlet.call.provide(n)
        }

        fun sourceId(): UUID = relay.outlet.waveState().sourceId

        fun highWater(): Long = relay.outlet.waveState().highWater
    }

    /**
     * BS-20 headline (`[KFX-08]`, `[KFX-09]`) — the construction `DISPUTES.md`
     * records as double-firing, made to fire exactly once.
     *
     * No checkpoint is taken here on purpose: the identity has to survive on a
     * journal that holds nothing but frames and frontier advances, which is why
     * the `sourceId` is *derived* rather than journaled. The checkpointed arm is
     * the next test.
     */
    @Test
    fun `a journaled source feeding an effectful sink fires each delta exactly once across a crash`() {
        val controller = SimulationController(seed = 11)
        val journal = InMemoryJournal() // "the disk": the only thing that survives
        val effects = mutableListOf<Int>() // in-process effect log ([KFX-24])
        val relayRef = CellRef(UUID.randomUUID())
        val notifierRef = CellRef(UUID.randomUUID())

        val before = World(controller, journal, relayRef, notifierRef, effects)
        controller.runToIdle()

        before.feed(1)
        before.feed(2)
        before.feed(3)
        controller.runToIdle()
        effects shouldBe listOf(1, 2, 3)

        // CRASH: host, registry and both live instances vanish; the journal does not
        val after = World(controller, journal, relayRef, notifierRef, effects)
        controller.runToIdle()
        after.host.recoverFrom(journal)
        controller.runToIdle()

        // The replayed input frames re-drove the rebuilt source, whose re-emissions
        // carried the identity the sink's restored frontier had recorded — suppressed,
        // not re-acted. Each logical delta fired exactly once ACROSS the crash.
        effects shouldBe listOf(1, 2, 3)

        // and post-recovery live traffic still reaches the sink (BS-21's other half:
        // the counter did not rewind below the frontier, so nothing live is eaten)
        after.feed(4)
        controller.runToIdle()
        effects shouldBe listOf(1, 2, 3, 4)
    }

    /**
     * The same headline over a **compacted** journal: the frames that produced
     * the pre-crash emissions are gone, so the counter can only come from the
     * checkpoint's `RECORD_OUTLET_WAVE`.
     */
    @Test
    fun `a checkpointed journaled source still fires each delta exactly once across a crash`() {
        val controller = SimulationController(seed = 12)
        val journal = InMemoryJournal()
        val effects = mutableListOf<Int>()
        val relayRef = CellRef(UUID.randomUUID())
        val notifierRef = CellRef(UUID.randomUUID())

        val before = World(controller, journal, relayRef, notifierRef, effects)
        controller.runToIdle()

        before.feed(1)
        before.feed(2)
        controller.runToIdle()
        before.host.checkpoint(journal) // compaction: 1 and 2's frames leave the WAL

        before.feed(3)
        controller.runToIdle()
        effects shouldBe listOf(1, 2, 3)
        before.highWater() shouldBe 3L

        val after = World(controller, journal, relayRef, notifierRef, effects)
        controller.runToIdle()
        after.host.recoverFrom(journal)
        controller.runToIdle()

        effects shouldBe listOf(1, 2, 3)
        // the tail replay re-derived exactly the counter it emitted pre-crash, so the
        // outlet is back at the pre-crash high-water — not below it, not past it
        after.highWater() shouldBe 3L

        after.feed(4)
        controller.runToIdle()
        effects shouldBe listOf(1, 2, 3, 4)
    }

    /**
     * BS-22 recovery arm (`[KFX-08]`, `[KFX-12]`, `[KFX-13]`) — **the decision,
     * asserted**: durable recovery is a *preserved-epoch continuation*, not a
     * fresh-epoch succession.
     *
     * Named for the arm it pins so the choice is legible from the test list and
     * not only from the diff. The three things that make it that arm, in order:
     * the recovered outlet emits under the *same* `sourceId` the pre-crash
     * instance emitted under; that id is the ref-derived one `[24-DUR-04]`
     * requires rather than a `randomUUID`; and — because 93 I-14 Rule S5 makes a
     * preserved-epoch continuation invisible to versioning — recovery emits
     * **no** `ReBaseline` supersession notice, which is what `[KFX-13]`'s
     * `WHERE` clause gates on and is therefore vacuous on this arm.
     */
    @Test
    fun `BS-22 - durable recovery is a preserved-epoch continuation, ref-derived and not re-baselined`() {
        val controller = SimulationController(seed = 13)
        val journal = InMemoryJournal()
        val effects = mutableListOf<Int>()
        val relayRef = CellRef(UUID.randomUUID())
        val notifierRef = CellRef(UUID.randomUUID())

        val before = World(controller, journal, relayRef, notifierRef, effects)
        controller.runToIdle()
        before.feed(1)
        before.feed(2)
        controller.runToIdle()

        val observedSourceId = before.sourceId()
        // the identity the network observed is derived from the outlet's ref — the
        // literal reading of [24-DUR-04] ("never randomUUID"), already true on the
        // LIVE path, which is the only way recovery can re-mint it ([KFX-12]
        // "consistently across journal, checkpoint, and live paths")
        observedSourceId shouldBe OutletWaveState.durable(before.relay.outlet.ref).sourceId
        before.highWater() shouldBe 2L

        val after = World(controller, journal, relayRef, notifierRef, effects)
        // watch every post-recovery emission of the rebuilt outlet
        val seen = mutableListOf<MessageContext>()
        after.relay.outlet.observe(PortRef.generate()) { seen += it }
        controller.runToIdle()
        after.host.recoverFrom(journal)
        controller.runToIdle()

        // [KFX-08]: the recovered outlet continues the pre-crash identity — it was NOT
        // freshly minted at random by the recovery path
        after.sourceId() shouldBe observedSourceId
        seen.map { it.timestamp.sourceId }.toSet() shouldBe setOf(observedSourceId)

        // ... and the replay re-derived the counters the network already saw, rather
        // than continuing past them (which is what would have re-fired the sink)
        seen.map { it.timestamp.counter } shouldBe listOf(1L, 2L)

        // [KFX-13] is WHERE-gated on the fresh-epoch arm: a preserved-epoch
        // continuation is invisible to versioning (93 I-14 Rule S5), so no emission
        // announces a supersession
        seen.none { it.reBaseline != null }.shouldBeTrue()

        // [KFX-10]/[KFX-11]: the first LIVE emission after recovery is strictly above
        // every counter the network observed pre-crash — a restored identity whose
        // counter restarted at 0 would have been silently eaten by the sink's frontier
        // as already-acted, turning the double-fire into effect LOSS.
        after.feed(3)
        controller.runToIdle()
        val live = seen.last()
        live.timestamp.sourceId shouldBe observedSourceId
        (live.timestamp.counter > 2L).shouldBeTrue()
        effects shouldBe listOf(1, 2, 3)
    }

    /**
     * `[KFX-14]` guard, kept honest at the seam this task actually moved: the
     * ref-derived epoch is installed for **journaled** cells only. A volatile
     * host's cell keeps 93 I-14 Rule S1's fresh-epoch default, so two
     * incarnations sharing a `CellRef` are still two disjoint sources — no
     * `(sourceId, counter)` reuse where nothing can prove counter continuity.
     *
     * (The wider `[KFX-14]`/`[KFX-15]` sweep — RESTART, replica/candidate spawn,
     * promotion — belongs to sibling feature computenet-yh6.1.4; this asserts
     * only the branch introduced here.)
     */
    @Test
    fun `a volatile cell's outlet still mints a fresh epoch per incarnation`() {
        val controller = SimulationController(seed = 14)
        val relayRef = CellRef(UUID.randomUUID())

        fun spawnVolatile(): RelayCell {
            val host = ManagedHost(scheduler = controller.scheduler())
            val relay = RelayCell(relayRef)
            host.managementInlet.call.spawn(relay)
            controller.runToIdle()
            return relay
        }

        val first = spawnVolatile()
        val second = spawnVolatile()

        (first.outlet.waveState().sourceId != second.outlet.waveState().sourceId).shouldBeTrue()
        (first.outlet.waveState().sourceId != OutletWaveState.durable(first.outlet.ref).sourceId)
            .shouldBeTrue()
    }
}
