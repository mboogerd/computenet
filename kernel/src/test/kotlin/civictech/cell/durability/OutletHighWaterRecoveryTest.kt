package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.MessageContext
import civictech.cell.ReBaselineEmitting
import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.cell.evolve.Effectful
import civictech.cell.control.Attention
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.IntakeBound
import civictech.cell.host.JournalRecords
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SaturationPolicy
import civictech.cell.host.SimulationController
import civictech.cell.host.SupervisionPolicy
import civictech.cell.link.Link
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.WireCodec
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
    class RelayCell(override val ref: CellRef) : Cell, ReBaselineEmitting {
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Int>>())
        val restartOutlet = registerPort("restartOutlet", FanOutlet.create<Consumer<String>>())
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    if (input == Int.MIN_VALUE) throw IllegalStateException("restart requested")
                    outlet.call.provide(input)
                }
            })
        }

        override fun reBaseline(supersedes: Set<UUID>, supersede: Boolean) {
            restartOutlet.reBaseline(supersedes, supersede) { provide("restart") }
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
            host.managementInlet.call.supervise(relay.ref, SupervisionPolicy.RESTART)
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

    /**
     * computenet-49i65 / qfi22-D10: a RESTART after the last checkpoint is part
     * of the durable history. Recovery must reproduce that exact epoch boundary,
     * not mint a third epoch while replaying the invocation that originally
     * failed. The tail then re-derives under the source ids the network actually
     * observed, so the `Effectful` frontier suppresses its direct replay instead
     * of seeing recovery-only positions and firing twice.
     */
    @Test
    fun `RESTART after the last checkpoint survives crash recovery with its epoch and ReBaseline announcement`() {
        val controller = SimulationController(seed = 23)
        val journal = InMemoryJournal()
        val effects = mutableListOf<Int>()
        val relayRef = CellRef(UUID.randomUUID())
        val notifierRef = CellRef(UUID.randomUUID())

        val before = World(controller, journal, relayRef, notifierRef, effects)
        controller.runToIdle()
        before.feed(1)
        before.feed(2)
        controller.runToIdle()
        effects shouldBe listOf(1, 2)

        // The checkpoint captures the original durable epoch. Everything below
        // it is the WAL tail that must replay across the RESTART boundary.
        before.host.checkpoint(journal)
        val preRestartSource = before.relay.outlet.waveState().sourceId

        val liveRestartNotices = mutableListOf<MessageContext>()
        before.relay.restartOutlet.observe(PortRef.generate()) { liveRestartNotices += it }
        before.feed(Int.MIN_VALUE)
        controller.runToIdle()
        before.host.supervisionAccounting().restarts shouldBe 1

        val postRestartSource = before.relay.outlet.waveState().sourceId
        val postRestartNoticeSource = before.relay.restartOutlet.waveState().sourceId
        (postRestartSource != preRestartSource).shouldBeTrue()
        liveRestartNotices.single().reBaseline?.supersedes?.contains(preRestartSource) shouldBe true

        // The fix is deliberately one additive record: exact triggering frame,
        // host generation, superseded lanes, and the fresh epoch per outlet.
        val restartRecord = journal.replay().mapNotNull(JournalRecords::decodeRestart)
            .single()
        restartRecord.cellRef shouldBe relayRef
        restartRecord.precedesFrameCount shouldBe 0
        restartRecord.generation shouldBe 1L
        restartRecord.supersedes shouldBe liveRestartNotices.single().reBaseline?.supersedes
        val trigger = WireCodec.decode(checkNotNull(restartRecord.triggerFramePayload))
        trigger.cellRef shouldBe relayRef
        trigger.portName shouldBe "inlet"
        trigger.invocation.args.single() shouldBe Int.MIN_VALUE
        restartRecord.outlets.associate { it.portName to it.sourceId } shouldBe mapOf(
            "outlet" to postRestartSource,
            "restartOutlet" to postRestartNoticeSource,
        )
        restartRecord.outlets.all { it.highWater == 0L }.shouldBeTrue()

        before.feed(3)
        before.feed(4)
        controller.runToIdle()
        effects shouldBe listOf(1, 2, 3, 4)

        // Compatibility control: this is the exact journal an older build would
        // have written — same checkpoint and frames, with the additive type-8
        // record absent. Its replay keeps the pre-change behavior (including the
        // known duplicate); the new kernel neither invents a record nor silently
        // changes the meaning of those existing bytes.
        val legacyJournal = InMemoryJournal().also { legacy ->
            legacy.reset(journal.replay().filter { JournalRecords.decodeRestart(it) == null })
        }
        val legacyEffects = mutableListOf(1, 2, 3, 4)
        val legacy = World(controller, legacyJournal, relayRef, notifierRef, legacyEffects)
        controller.runToIdle()
        legacy.host.recoverFrom(legacyJournal)
        controller.runToIdle()
        legacyEffects shouldBe listOf(1, 2, 3, 4, 3, 4)
        legacyJournal.replay().mapNotNull(JournalRecords::decodeRestart) shouldBe emptyList()

        // CRASH: only the journal survives. Observe recovery itself, not the live
        // announcement above, so a silent reconstruction cannot satisfy the test.
        val after = World(controller, journal, relayRef, notifierRef, effects)
        controller.runToIdle()
        val recoveredRestartNotices = mutableListOf<MessageContext>()
        after.relay.restartOutlet.observe(PortRef.generate()) { recoveredRestartNotices += it }
        after.host.recoverFrom(journal)
        controller.runToIdle()

        effects shouldBe listOf(1, 2, 3, 4)
        after.host.generationOf(relayRef) shouldBe 1L
        after.relay.outlet.waveState().sourceId shouldBe postRestartSource
        after.relay.outlet.waveState().highWater shouldBe 2L
        recoveredRestartNotices.single().timestamp.sourceId shouldBe postRestartNoticeSource
        recoveredRestartNotices.single().reBaseline?.supersedes?.contains(preRestartSource) shouldBe true
    }

    /**
     * computenet-49i65 review: a `PORT_PROTOCOL` frame is never journaled
     * (`accept` returns before the tee), so a metadata-plane handler failure has
     * no appended bytes to name as its trigger. Re-encoding it instead threw
     * `PORT_PROTOCOL requires a WireEdgeLink` out of supervision. Type 8 records
     * a triggerless ordered boundary in that case: supervision never serializes
     * the metadata frame, while recovery can still rotate to the recorded epoch
     * before replaying the post-restart tail.
     */
    @Test
    fun `a metadata-plane RESTART writes an ordered boundary and survives crash recovery`() {
        val controller = SimulationController(seed = 29)
        val journal = InMemoryJournal()
        val effects = mutableListOf<Int>()
        val relayRef = CellRef(UUID.randomUUID())
        val notifierRef = CellRef(UUID.randomUUID())
        val world = World(controller, journal, relayRef, notifierRef, effects)
        controller.runToIdle()
        world.feed(1)
        controller.runToIdle()
        val notices = mutableListOf<MessageContext>()
        world.relay.restartOutlet.observe(PortRef.generate()) { notices += it }
        val preRestartSource = world.relay.outlet.waveState().sourceId

        ProtocolSupport.of(world.relay.inlet).handle(Protocols.Attention) { _, _ ->
            throw IllegalStateException("protocol handler blew up")
        }
        val selfLink = object : Link {
            override val id: UUID = UUID.randomUUID()
            override val from = world.relay.inlet.ref
            override val to = world.relay.inlet.ref
            override fun unlink() = Unit
        }

        // The metadata task runs at band 0 and overtakes this already-journaled
        // data frame, which remains staged at band 20. Live scheduling is the
        // contract: the RESTART boundary therefore precedes frame 2 even though
        // its type-8 record is appended after frame 2's WAL record.
        world.feed(2)
        world.host.enqueueHostedInvocation(
            HostedPortInvocation(
                world.relay.ref,
                "inlet",
                HostedPortInvocation.Type.PORT_PROTOCOL,
                Invocation("", emptyList(), emptyList()),
                protocolId = Protocols.Attention,
                protocolLink = selfLink,
                protocolMessage = Attention(.5f),
            ),
        )
        controller.runToIdle()

        world.host.supervisionAccounting().restarts shouldBe 1
        val postRestartSource = world.relay.outlet.waveState().sourceId
        val postRestartNoticeSource = world.relay.restartOutlet.waveState().sourceId
        (postRestartSource != preRestartSource).shouldBeTrue()
        notices.single().reBaseline?.supersedes?.contains(preRestartSource) shouldBe true
        val restartRecord = journal.replay().mapNotNull(JournalRecords::decodeRestart).single()
        restartRecord.cellRef shouldBe relayRef
        restartRecord.triggerFramePayload shouldBe null
        restartRecord.precedesFrameCount shouldBe 1
        restartRecord.generation shouldBe 1L
        restartRecord.supersedes shouldBe notices.single().reBaseline?.supersedes
        restartRecord.outlets.associate { it.portName to it.sourceId } shouldBe mapOf(
            "outlet" to postRestartSource,
            "restartOutlet" to postRestartNoticeSource,
        )
        restartRecord.outlets.all { it.highWater == 0L }.shouldBeTrue()

        effects shouldBe listOf(1, 2)
        world.feed(3)
        controller.runToIdle()
        val liveEffects = effects.toList()
        val liveWave = world.relay.outlet.waveState()
        liveEffects shouldBe listOf(1, 2, 3)
        liveWave.sourceId shouldBe postRestartSource
        liveWave.highWater shouldBe 2L

        val after = World(controller, journal, relayRef, notifierRef, effects)
        controller.runToIdle()
        val recoveredNotices = mutableListOf<MessageContext>()
        after.relay.restartOutlet.observe(PortRef.generate()) { recoveredNotices += it }
        after.host.recoverFrom(journal)
        controller.runToIdle()

        effects shouldBe liveEffects
        after.host.supervisionAccounting().restarts shouldBe 1
        after.host.generationOf(relayRef) shouldBe 1L
        after.relay.outlet.waveState() shouldBe liveWave
        recoveredNotices.single().timestamp.sourceId shouldBe postRestartNoticeSource
        recoveredNotices.single().reBaseline?.supersedes?.contains(preRestartSource) shouldBe true

        after.feed(4)
        controller.runToIdle()
        effects shouldBe listOf(1, 2, 3, 4)
        effects.count { it == 4 } shouldBe 1
    }

    /**
     * computenet-srkyq review: the frames a metadata-plane RESTART overtakes may be
     * REPLAYED frames still staged by an earlier recovery, not only live-journaled
     * ones. They are records of the same journal, so a second recovery must put the
     * boundary ahead of them too; otherwise it replays them on the old epoch, recovers
     * a short fresh-epoch high-water and suppresses the next live effect ([KFX-11]).
     */
    @Test
    fun `a metadata-plane RESTART that overtakes replayed frames survives a second crash`() {
        val controller = SimulationController(seed = 37)
        val journal = InMemoryJournal()
        val effects = mutableListOf<Int>()
        val relayRef = CellRef(UUID.randomUUID())
        val notifierRef = CellRef(UUID.randomUUID())
        val first = World(controller, journal, relayRef, notifierRef, effects)
        controller.runToIdle()
        first.feed(1)
        first.feed(2)
        controller.runToIdle()

        // Crash 1. Recovery only STAGES frames 1 and 2; the failing metadata task
        // (band 0) runs ahead of both (band 20) and restarts the relay first.
        val recovering = World(controller, journal, relayRef, notifierRef, effects)
        controller.runToIdle()
        recovering.host.recoverFrom(journal)
        ProtocolSupport.of(recovering.relay.inlet).handle(Protocols.Attention) { _, _ ->
            throw IllegalStateException("protocol handler blew up")
        }
        val selfLink = object : Link {
            override val id: UUID = UUID.randomUUID()
            override val from = recovering.relay.inlet.ref
            override val to = recovering.relay.inlet.ref
            override fun unlink() = Unit
        }
        recovering.host.enqueueHostedInvocation(
            HostedPortInvocation(
                recovering.relay.ref,
                "inlet",
                HostedPortInvocation.Type.PORT_PROTOCOL,
                Invocation("", emptyList(), emptyList()),
                protocolId = Protocols.Attention,
                protocolLink = selfLink,
                protocolMessage = Attention(.5f),
            ),
        )
        controller.runToIdle()
        recovering.host.supervisionAccounting().restarts shouldBe 1
        journal.replay().mapNotNull(JournalRecords::decodeRestart).single().precedesFrameCount shouldBe 2
        recovering.feed(3)
        controller.runToIdle()
        // liveEffects includes recovery 1's own re-derivations of 1 and 2 on the fresh
        // epoch (a restart mid-recovery does that on origin/main too); this test pins
        // only that the second recovery reproduces the live run and loses nothing.
        val liveEffects = effects.toList()
        val liveWave = recovering.relay.outlet.waveState()

        // Crash 2.
        val after = World(controller, journal, relayRef, notifierRef, effects)
        controller.runToIdle()
        after.host.recoverFrom(journal)
        controller.runToIdle()

        effects shouldBe liveEffects
        after.relay.outlet.waveState() shouldBe liveWave
        after.feed(4)
        controller.runToIdle()
        effects shouldBe liveEffects + 4
    }

    /**
     * Wire-capable (T06 §B needs the journal to actually encode/decode these frames,
     * same reason `TwoWriterDurabilityTest.LogApi` is `@Contract`): [CoalesceRelayCell]'s
     * inlet, so `Invocation.of` can populate `contractId`/`methodId` from
     * [civictech.nature.ContractRegistry] for the hand-built, explicit-context frames
     * [CoalesceWorld.feed] journals below.
     */
    @civictech.gen.wire.Contract
    interface CoalesceRelayApi {
        fun provide(delta: SetDelta<Int>)
    }

    /**
     * The journaled source for the computenet-ggxrq coalesce scenario below: unlike
     * [RelayCell] (one `Int` per frame), its inlet takes a [SetDelta] and reacts
     * **per added element** — `outlet.call.provide` once per key in `adds`, in sorted
     * order for a deterministic effect sequence. That per-element shape is exactly
     * the one the council's DECIDED verdict (computenet-ggxrq, 2026-10-07) requires for
     * the "exactly-once, not merely never-zero" bound: a `SaturationPolicy.Coalesce`
     * merge of two originals this handler reacts to still fires once per original
     * ELEMENT, so counting the merge's originals in `precedesFrameCount` (option A's
     * mechanism) makes replay reproduce live EXACTLY for this shape — see the test's
     * own KDoc for the weaker-guarantee carve-out this shape does NOT need.
     */
    class CoalesceRelayCell(override val ref: CellRef) : Cell, ReBaselineEmitting {
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Int>>())
        val restartOutlet = registerPort("restartOutlet", FanOutlet.create<Consumer<String>>())
        val inlet = registerPort("inlet", FanInlet.create<CoalesceRelayApi>())

        init {
            inlet.serve(object : CoalesceRelayApi {
                override fun provide(delta: SetDelta<Int>) {
                    delta.adds.keys.sorted().forEach { element ->
                        if (element == Int.MIN_VALUE) throw IllegalStateException("restart requested")
                        // originate (not outlet.call.provide), so EACH element mints its own
                        // fresh wave: a plain reactive provide() for every element of one
                        // merged delivery would inherit the SAME incoming timestamp for all
                        // of them, and the Effectful sink's own dedup frontier (keyed on that
                        // timestamp) would then silently suppress every element but the
                        // first — exactly the shape the park comment's probe avoids by
                        // minting "from the relay's own outlet counter" per element.
                        outlet.originate { provide(element) }
                    }
                }
            })
        }

        override fun reBaseline(supersedes: Set<UUID>, supersede: Boolean) {
            restartOutlet.reBaseline(supersedes, supersede) { provide("restart") }
        }
    }

    /**
     * [CoalesceRelayCell] on its own journaled, [IntakeBound]ed host A; [NotifierCell]
     * on a SEPARATE journaled host B with no bound, the two joined only by a shared
     * [LocationRegistry] — exactly the park comment's probe shape, and NOT the
     * single-host convenience [World] uses elsewhere in this file. Measured why it
     * matters: co-hosting both cells (one shared [IntakeBound]'s `dataQueuedCount`
     * sums across every cell on the host) lets the merged entry's SECOND per-element
     * emission (`outlet.call.provide(22)`, downstream of the first, `provide(2)`,
     * which already re-saturates the host once its own frame stages) throw
     * `IntakeSaturatedException` back INTO [CoalesceRelayCell]'s own handler — a
     * second, spurious RESTART that silently drops the first element's effect. That
     * is the double-restart confound the park comment's probe names from an earlier,
     * inconclusive attempt (reproduced here while iterating on this test: co-hosted,
     * `restarts` came back `2`, not the expected `1`). Host B's independent
     * `dataLock`/`AttentionScheduler` has no [IntakeBound] at all, so the
     * `Effectful` sink's own inbound frames are never gated by host A's saturation.
     */
    private class CoalesceWorld(
        controller: SimulationController,
        val journalA: InMemoryJournal,
        val journalB: InMemoryJournal,
        relayRef: CellRef,
        notifierRef: CellRef,
        val effects: MutableList<Int>,
    ) {
        private val registry = LocationRegistry()
        val hostA = ManagedHost(
            scheduler = controller.scheduler(),
            registry = registry,
            journal = journalA,
            intakeBound = IntakeBound(highWater = 1, lowWater = 0, policy = SaturationPolicy.Coalesce),
        )
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registry, journal = journalB)
        val relay = CoalesceRelayCell(relayRef)
        val notifier = NotifierCell(notifierRef, effects)

        /** One fixed synthetic upstream source/port for every [feed] call, so repeated
         * calls at the SAME [counter] land in the same source+wave slot — the exact
         * condition `IntakeControl.coalesce` requires to merge two entries. */
        private val feedSource = PortRef.generate()

        private val provideMethod = CoalesceRelayApi::class.java.methods.single { it.name == "provide" }

        init {
            hostB.managementInlet.call.spawn(notifier)
            hostA.managementInlet.call.spawn(relay)
            hostA.managementInlet.call.supervise(relay.ref, SupervisionPolicy.RESTART)
            val sink = (HostedCellProxy.create(notifierRef, registry, NotifierProxy::class.java) as NotifierProxy)
                .inlet.call
            relay.outlet.subscribe(Use.fixed(sink, PortRef.generate()))
        }

        /** A root-driven `SetDelta` add of one [element], carrying an explicit wave
         * [counter] (unlike [World.feed]'s context-free root frame) so two calls at
         * the same counter are the same source+wave slot a saturated intake coalesces. */
        fun feed(element: Int, counter: Long) {
            val context = MessageContext(Timestamp(feedSource.id, counter), feedSource)
            val delta = SetDelta(adds = mapOf(element to setOf(Timestamp(feedSource.id, counter))))
            hostA.enqueueHostedInvocation(
                HostedPortInvocation(
                    relay.ref,
                    "inlet",
                    HostedPortInvocation.Type.PORT_API,
                    // Invocation.of (not the raw constructor) so contractId/methodId are
                    // populated from ContractRegistry — WireCodec.encode (the journal
                    // frame path) requires them.
                    Invocation.of(provideMethod, arrayOf(delta), context),
                ),
            )
        }
    }

    /**
     * computenet-ggxrq (I-22 amendment, council DECIDED 2026-10-07, option C): a
     * triggerless metadata-plane RESTART overtaking a `SaturationPolicy.Coalesce`
     * staged entry must count every coalesced ORIGINAL in `precedesFrameCount`
     * (option A's mechanism), not the merge's own identity — `IntakeControl.coalesce`
     * replaces the staged queue entry with a NEW `HostedPortInvocation` that neither
     * `journaledFrames` nor `replayedFrames` ever keyed, so a bare identity count
     * undercounted it as 0 even though the WAL holds one `RECORD_FRAME` per original
     * (coalescing is acceptance, not loss: `ManagedHost`'s saturated-Coalesce branch
     * journals every incoming frame before merging it away). Measured on this bead
     * before the fix: `precedesFrameCount=0`, live effects `[1, 2, 22, 3]`, recovered
     * `[1, 2, 3, 2, 22]` with the post-recovery live frame SILENTLY LOST (`[KFX-11]`) —
     * a regression vs origin/main, which only duplicated.
     *
     * The council's documented weaker guarantee applies only to a handler that emits
     * once per MERGED DELTA (replay would then reproduce k waves where live produced
     * one); [CoalesceRelayCell] is the OTHER shape this amendment names — one emission
     * per COALESCED ELEMENT — for which counting the originals makes replay reproduce
     * live EXACTLY, not merely without loss. This test therefore pins the council's
     * caveat directly: the post-recovery frame fires EXACTLY once, and the full
     * recovered effect sequence equals the live one position-for-position, not just
     * "never zero".
     */
    @Test
    fun `a metadata-plane RESTART that overtakes a coalesced staged entry survives crash recovery without dropping the post-recovery frame`() {
        val controller = SimulationController(seed = 49)
        val journalA = InMemoryJournal()
        val journalB = InMemoryJournal()
        val effects = mutableListOf<Int>()
        val relayRef = CellRef(UUID.randomUUID())
        val notifierRef = CellRef(UUID.randomUUID())
        val world = CoalesceWorld(controller, journalA, journalB, relayRef, notifierRef, effects)
        controller.runToIdle()

        // Drain frame 1 while intake is OPEN, so it never competes with the saturation
        // window below (`[1]` is delivered and forgotten long before the merge).
        world.feed(1, counter = 1)
        controller.runToIdle()
        effects shouldBe listOf(1)

        ProtocolSupport.of(world.relay.inlet).handle(Protocols.Attention) { _, _ ->
            throw IllegalStateException("protocol handler blew up")
        }
        val selfLink = object : Link {
            override val id: UUID = UUID.randomUUID()
            override val from = world.relay.inlet.ref
            override val to = world.relay.inlet.ref
            override fun unlink() = Unit
        }

        // feed(2) saturates the host (highWater = 1); feed(22) lands in the SAME
        // source+wave slot (counter 7) and is coalesced into the staged {2} entry —
        // the WAL still gets a RECORD_FRAME for each original.
        world.feed(2, counter = 7)
        world.feed(22, counter = 7)

        // The metadata task runs at band 0 and overtakes the already-coalesced,
        // still-staged entry at band 20 — the same overtaking shape as this file's
        // other metadata-plane RESTART tests, now landing on a merge instead of a
        // plain staged frame.
        world.hostA.enqueueHostedInvocation(
            HostedPortInvocation(
                world.relay.ref,
                "inlet",
                HostedPortInvocation.Type.PORT_PROTOCOL,
                Invocation("", emptyList(), emptyList()),
                protocolId = Protocols.Attention,
                protocolLink = selfLink,
                protocolMessage = Attention(.5f),
            ),
        )
        controller.runToIdle()

        world.hostA.supervisionAccounting().restarts shouldBe 1
        val restartRecord = journalA.replay().mapNotNull(JournalRecords::decodeRestart).single()
        restartRecord.triggerFramePayload shouldBe null
        // computenet-ggxrq: BOTH coalesced originals, not the pre-fix 0.
        restartRecord.precedesFrameCount shouldBe 2

        world.feed(3, counter = 8)
        controller.runToIdle()
        val liveEffects = effects.toList()
        liveEffects shouldBe listOf(1, 2, 22, 3)

        // Crash: both hosts, the registry and both live instances vanish; the journals do not.
        val after = CoalesceWorld(controller, journalA, journalB, relayRef, notifierRef, effects)
        controller.runToIdle()
        after.hostA.recoverFrom(journalA)
        after.hostB.recoverFrom(journalB)
        controller.runToIdle()

        // Exact agreement, not merely "no loss": for this per-element handler shape
        // the fix reproduces live position-for-position.
        effects shouldBe liveEffects

        after.feed(4, counter = 9)
        controller.runToIdle()
        effects shouldBe liveEffects + 4
        // The council's caveat: exactly-once for a per-element/single-emit handler,
        // not merely "at least once".
        effects.count { it == 4 } shouldBe 1
    }
}
