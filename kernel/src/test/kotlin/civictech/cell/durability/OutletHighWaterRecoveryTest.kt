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
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * KFX feature 2, task 2 (BS-21) — **a recovered outlet's counter high-water
 * survives recovery, so live post-recovery traffic is never suppressed as
 * already-acted**. Task computenet-yh6.1.2.2; the mechanism under test
 * (`HostDurability.installDurableEpochs`, `RECORD_OUTLET_WAVE`/
 * `OutletWaveRecord`, `OutletWaveState.durable`) was landed by sibling task
 * computenet-yh6.1.2.1 per its written `[KFX-12]` decision: durable recovery
 * is a **preserved-epoch continuation** — a ref-derived `sourceId`, plus a
 * checkpoint-carried counter high-water restored via `FanOutlet.adoptWaveState`.
 *
 * `[KFX-10]`: the outlet's counter high-water is restored together with its
 * identity. `[KFX-11]`: the forbidden outcome — an identity reused while its
 * counter restarts from zero, so a downstream `Effectful` frontier suppresses
 * *live* post-recovery traffic as already-acted (silent effect loss, strictly
 * worse than the double-fire this feature otherwise closes) — must not occur.
 *
 * Every exactly-once/never-suppressed assertion here is against the in-process
 * [World.effects] log ([KFX-24]) — never restated as an end-to-end external
 * exactly-once claim.
 */
class OutletHighWaterRecoveryTest {

    /** The journaled source whose outlet wave identity must survive the crash. */
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

    /** The `Effectful` boundary: every `provide` acts on [world], which outlives any instance. */
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
     * One incarnation of the graph (the `EffectfulRecoveryTest`/`OutletWaveRecoveryTest`
     * rig shape): source and sink co-hosted on a durable host over [journal] — the
     * whole-host degenerate tee. A "crash" is building a fresh [World] over the same
     * journal and the same [CellRef]s: every live instance is discarded, only the
     * journal survives.
     */
    private class World(
        controller: SimulationController,
        val journal: InMemoryJournal,
        relayRef: CellRef,
        notifierRef: CellRef,
        val effects: MutableList<Int>,
    ) {
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val relay = RelayCell(relayRef)
        val notifier = NotifierCell(notifierRef, effects)

        init {
            host.managementInlet.call.spawn(notifier)
            host.managementInlet.call.spawn(relay)
            // source -> sink through the host intake, so the sink's inlet sees a
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

        fun highWater(): Long = relay.outlet.waveState().highWater
    }

    /**
     * BS-21 (preserved-identity arm), `[KFX-10]`: given a recovered outlet whose
     * identity was preserved, its counter high-water is restored together with it —
     * asserted both directly on [FanOutlet.waveState] and boundary-observably: a
     * post-recovery add on the journaled source fires its effect at the `Effectful`
     * sink exactly once (not zero), with a counter strictly above every counter the
     * network observed pre-crash.
     */
    @Test
    fun `BS-21 preserved-identity arm - the counter high-water survives recovery so the next emission counter exceeds every pre-crash counter`() {
        val controller = SimulationController(seed = 21)
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
        val preCrashHighWater = before.highWater()
        preCrashHighWater shouldBe 3L

        // CRASH: host, registry and both live instances vanish; the journal does not
        val after = World(controller, journal, relayRef, notifierRef, effects)
        controller.runToIdle()
        after.host.recoverFrom(journal)
        controller.runToIdle()

        // [KFX-10] direct: the counter high-water came back WITH the identity, not
        // reset to 0 by the replay's re-derivation of pre-crash counters
        after.highWater() shouldBe preCrashHighWater

        // [KFX-10]/[KFX-11] boundary: watch every post-recovery emission of the
        // rebuilt outlet, then drive one live delta through it
        val seen = mutableListOf<MessageContext>()
        after.relay.outlet.observe(PortRef.generate()) { seen += it }
        after.feed(4)
        controller.runToIdle()

        // the live emission's counter is strictly greater than every counter the
        // network observed pre-crash from this identity
        (seen.single().timestamp.counter > preCrashHighWater).shouldBeTrue()
        // and it reached the sink exactly once, not zero times (the guard the next
        // test names directly) and not twice (the double-fire this feature also closes)
        effects shouldBe listOf(1, 2, 3, 4)
    }

    /**
     * `[KFX-11]` guard, made genuinely non-vacuous: the forbidden outcome is live
     * post-recovery traffic suppressed as already-acted by the restored frontier —
     * observed as the post-recovery key's effect count being exactly 1, never 0.
     *
     * A naive "restore the identity, forget the counter" fix passes a *rotation-free*
     * version of this scenario by accident: re-deriving the outlet's ref-derived
     * `sourceId` on recovery happens to reproduce the correct high-water too, because
     * nothing ever moved the outlet off its derived epoch. This test does not let that
     * accident stand: before the checkpoint, the outlet is rotated to a fresh epoch —
     * exactly what RESTART supervision's `mintFreshEpoch` or a drain/migration/
     * promotion `adoptWaveState` does ([KFX-14]/[KFX-15], untouched by this task) — so
     * the epoch *in force* at checkpoint time is no longer the ref-derived one. Only
     * recording (and restoring) the actual `(sourceId, highWater)` pair survives that;
     * re-deriving the `sourceId` from the ref on restore would pair the *derived*
     * identity with the *rotated* epoch's high-water, re-issuing `(sourceId, counter)`
     * pairs the derived lane already spent — which the sink's restored frontier reads
     * as already-acted, i.e. exactly the silent effect loss `[KFX-11]` forbids.
     *
     * Non-vacuity was confirmed by hand against a reverted mechanism (see the task's
     * final report): with `restoreOutletWave` changed to re-derive
     * `OutletWaveState.durable(outlet.ref)` instead of adopting the recorded
     * `(sourceId, highWater)`, this test's final assertion fails with an effect count
     * of 0, not 1 — the post-recovery delta is eaten by the restored frontier.
     */
    @Test
    fun `KFX-11 guard - live post-recovery traffic is never suppressed as already-acted, even across an epoch rotation before the checkpoint`() {
        val controller = SimulationController(seed = 22)
        val journal = InMemoryJournal()
        val effects = mutableListOf<Int>()
        val relayRef = CellRef(UUID.randomUUID())
        val notifierRef = CellRef(UUID.randomUUID())

        val before = World(controller, journal, relayRef, notifierRef, effects)
        controller.runToIdle()

        // traffic under the derived epoch — the frontier now records counters 1..2
        // against the ref-derived sourceId
        before.feed(1)
        before.feed(2)
        controller.runToIdle()
        effects shouldBe listOf(1, 2)

        // rotate the outlet OFF its derived epoch before any checkpoint is taken —
        // the exact move [KFX-14]/[KFX-15] leave alone and the reason the epoch must
        // be recorded in force rather than re-derived on restore
        before.relay.outlet.mintFreshEpoch()

        // traffic under the ROTATED epoch — the frontier now also records counters
        // 1..1 against the rotated sourceId
        before.feed(3)
        controller.runToIdle()
        effects shouldBe listOf(1, 2, 3)

        // checkpoint captures the epoch IN FORCE (the rotated one) unconditionally
        before.host.checkpoint(journal)

        // CRASH
        val after = World(controller, journal, relayRef, notifierRef, effects)
        controller.runToIdle()
        after.host.recoverFrom(journal)
        controller.runToIdle()

        // recovery re-derives exactly the pre-crash counters under the rotated
        // epoch's identity — no double-fire
        effects shouldBe listOf(1, 2, 3)

        // [KFX-11]: live post-recovery traffic on the rotated (now recovered) identity
        // is delivered, not suppressed as already-acted — the post-recovery key's
        // effect count is exactly 1, never 0
        after.feed(4)
        controller.runToIdle()
        effects.count { it == 4 } shouldBe 1
        effects shouldBe listOf(1, 2, 3, 4)
    }
}
