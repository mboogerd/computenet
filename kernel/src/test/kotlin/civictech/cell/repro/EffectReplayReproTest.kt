package civictech.cell.repro

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.evolve.Effectful
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.durability.InMemoryJournal
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
 * **C-9 effect replay: BS-1, BS-4 and BS-5** (`[CHA2-10]`, `[CHA2-13]`, `[CHA2-14]`, and the
 * C-9 half of `[CHA2-02]`).
 *
 * The adjudication these three reproductions consume is `doc/evidence-lane-findings.md` →
 * "C-9 — effect replay", recorded by `computenet-umx.1.1`, plus this task's own entry
 * ("`computenet-umx.1.3` — C-9 reproductions"). Its verdict, **re-verified against the code
 * by this task rather than inherited**:
 *
 * - the processed frontier is landed and durable — `HostDurability.processedFrontier`,
 *   `alreadyProcessed`, `advanceAndJournalFrontier` (KDoc "G-59, fixes C-9"), consulted by
 *   the `PORT_API` branch of `ManagedHost.deliver` and advanced beside the delivery. BS-1
 *   pins it and **passes**;
 * - the journaled-source double-fire this feature planned as BS-4's standing expected
 *   failure was **fixed** by commit `34892d9` (replay-stable outlet wave identity,
 *   `FanOutlet.waveState`/`adoptWaveState`, `OutletWaveState.durable`), which
 *   `concord/corpus/DISPUTES.md`'s G-59/C-9 boundary heading now records as RESOLVED for
 *   that slice. BS-4 is therefore written **unweakened and unannotated**, with PASS as the
 *   accepted outcome — that pass is the evidence `computenet-yh6.1.5`/`[KFX-22]` consumes;
 * - the baseline exemption BS-5 was filed to probe (`link/CatchUp.kt`'s "does **not** test
 *   `ctx.baseline`") was **decided and implemented** by `computenet-yh6.1.3.4` as
 *   `[24-DUR-07]`/`[24-DUR-08]`. BS-5 is consequently a pin of a decided rule with a
 *   recorded answer, not a probe with an unknown one.
 *
 * ## Why nothing here is annotated
 *
 * `@ExpectedFailure` (`computenet-umx.1.2`) fails the build when its body **passes**
 * (`[CHA2-44]`), so an annotation is a falsifiable claim that a divergence is real today.
 * All three reproductions below were run against the kernel at this task's base commit and
 * observed to pass; annotating any of them would redden the build for a defect that is not
 * there. Equally, nothing is softened, re-seeded or narrowed to manufacture a failure the
 * feature's §0 predicted (BS-13, `[CHA2-47]`) — the constructions are the ones the feature
 * specifies, and the assertions are equalities over the whole external effect log rather
 * than "at most once" counts that a zero-fire regression would satisfy vacuously.
 *
 * ## What the external effect log is, and what it is not
 *
 * Every exactly-once claim here is against an in-process [MutableList] that outlives the
 * host, the registry and every cell instance — the `EffectfulRecoveryTest`/`[KFX-24]` shape.
 * A crash discards the instance, so only a log outside the instance lifecycle can see a
 * *true* double-fire. It is **not** an end-to-end external exactly-once claim; that ceiling
 * is 93 I-7's and belongs to CON1.
 *
 * ## `[CHA2-04]` / `[CHA2-26]`: no rig, and no rig substitute
 *
 * CHA1's fault-injection rig does not exist on `main` (the adjudication's "CHA1's rig does
 * not exist" section). Nothing here introduces one: a "crash" is the idiom the landed
 * durability tests already use — build a fresh [SimulationController] and a fresh
 * [ManagedHost] over the same [InMemoryJournal] and the same [CellRef]s, so every live
 * instance is discarded and only the journal survives. The only test-local helpers are the
 * fixtures and [CoHostedGraph] below; nothing here is a reusable injector, journal
 * decorator, crash harness, artifact format or sweep.
 */
class EffectReplayReproTest {

    // ------------------------------------------------------------------
    // Fixtures. Deliberately NOT reused from
    // kernel/src/test/kotlin/civictech/cell/durability/{EffectfulRecoveryTest,
    // OutletWaveRecoveryTest,EffectfulBaselineGuardTest}.kt: the evidence lane keeps its
    // own, so a change to a fixing lane's exit test cannot silently reshape a reproduction,
    // and vice versa (the citation discipline computenet-umx.1.4/.1.5 applied).
    // ------------------------------------------------------------------

    /** A **volatile** source: never spawned on a host, so the crash discards it entirely. */
    private class VolatileSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Int>>())

        fun emit(n: Int) = outlet.call.provide(n)
    }

    /**
     * A **journaled** source (BS-4): driven through the host intake, so its input frames land
     * in the WAL and are replayed on recovery, and re-emitting each delta from its own
     * [FanOutlet] — the emission whose wave identity has to survive the crash.
     */
    private class JournaledSource(override val ref: CellRef) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Int>>())
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(
                object : Consumer<Int> {
                    override fun provide(input: Int) {
                        outlet.call.provide(input)
                    }
                },
            )
        }
    }

    /** The effect boundary: every `provide` acts on [world], which outlives any instance. */
    private class NotifierCell(override val ref: CellRef, private val world: MutableList<Int>) : Cell, Effectful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(
                object : Consumer<Int> {
                    override fun provide(input: Int) {
                        world += input
                    }
                },
            )
        }
    }

    /**
     * BS-5's control: a plain, **non-`Effectful`** cell on the same durable host, so its
     * frames ride the same WAL and are replayed by the same `recoverFrom` — but no frontier
     * guard stands in front of it. It records, per frame it receives, that frame's
     * `(counter, carries a baseline)`, which is how the reproduction observes the PN-2 stamp
     * on a replayed frame that the `Effectful` inlet beside it suppressed and therefore never
     * delivered to a handler at all.
     */
    private class ContextRecorder(
        override val ref: CellRef,
        private val seen: MutableList<Pair<Long, Boolean>>,
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(
                object : Consumer<Int> {
                    override fun provide(input: Int) {
                        val ctx = CurrentContext.get()
                        seen += (ctx?.timestamp?.counter ?: -1L) to (ctx?.baseline != null)
                    }
                },
            )
        }
    }

    private interface SinkProxy {
        val inlet: Use<Consumer<Int>>
    }

    private interface SourceProxy {
        val inlet: Use<Consumer<Int>>
    }

    private fun inletOn(host: ManagedHost, ref: CellRef): Consumer<Int> =
        (HostedCellProxy.create(ref, host, SinkProxy::class.java) as SinkProxy).inlet.call

    /** An ordinary live wave frame from an external stamped ingress on lane [source]. */
    private fun live(source: UUID, counter: Long) =
        MessageContext(Timestamp(source, counter), PortRef.generate())

    // ------------------------------------------------------------------
    // BS-1 ([CHA2-10], the C-9 half of [CHA2-02]) — the landed core, pinned.
    // ------------------------------------------------------------------

    /**
     * **BS-1 — the C-9 core holds: frontier suppression on replay.** Expected to pass; its
     * purpose is that a regression in the landed mechanism is caught here and not only by the
     * fix's own tests (`[CHA2-02]`).
     *
     * The feature's construction, clause by clause, and how each is realized:
     *
     * - *a durable host with a journaled `Effectful` sink that has applied `(s, 1..7)`* — the
     *   notifier is spawned on a host with an [InMemoryJournal], and all seven emissions are
     *   drained before the crash, so the frontier records `(s, 7)` for its inlet;
     * - *a **volatile** source, discarded by the crash* — [VolatileSource] is never spawned
     *   on the host, so nothing about it is journaled and no incarnation of it comes back;
     * - *the host crashes **mid-drain*** — an upstream retransmit of positions 6 and 7 is
     *   pushed at the sink and **not drained**. `ManagedHost` journals a hosted frame
     *   synchronously at intake and delivers it on a later scheduler task, so the crash lands
     *   between the tee and the delivery: two frames sit in the WAL that no instance ever
     *   acted on. Retransmits rather than fresh positions on purpose — a *novel* undelivered
     *   frame is journal tail that [24-DUR-05] requires to fire on replay, which would make
     *   the "unchanged in size" clause below false for a correct kernel;
     * - *the external effect log is unchanged in size across replay* — the assertion is a
     *   full-log equality, not a size comparison, so effect **loss** during replay would fail
     *   it too;
     * - *and advances only for post-recovery invocations* — a fresh volatile source (the
     *   pre-crash one died with the crash) drives the recovered sink and its delta lands.
     */
    @Test
    fun `BS-1 a mid-drain crash replays the journal without re-firing or losing an effect`() {
        val journal = InMemoryJournal() // "the disk": the only thing that survives the crash
        val effects = mutableListOf<Int>() // the external effect log, outside any instance
        val notifierRef = CellRef(UUID.randomUUID())

        val preCrash = SimulationController(seed = 91)
        val crashed = ManagedHost(scheduler = preCrash.scheduler(), journal = journal)
        crashed.managementInlet.call.spawn(NotifierCell(notifierRef, effects))
        preCrash.runToIdle()

        val source = VolatileSource()
        val emitted = mutableListOf<MessageContext>()
        source.outlet.observe(PortRef.generate()) { emitted += it }
        source.outlet.subscribe(Use.fixed(inletOn(crashed, notifierRef), PortRef.generate()))

        (1..7).forEach { source.emit(it) }
        preCrash.runToIdle()

        effects shouldBe listOf(1, 2, 3, 4, 5, 6, 7)
        emitted.map { it.timestamp.counter } shouldBe (1L..7L).toList()
        emitted.map { it.timestamp.sourceId }.toSet().size shouldBe 1

        // MID-DRAIN: an upstream retransmit of positions 6 and 7 — journaled at intake,
        // never delivered, because this controller is abandoned where it stands.
        val sink = inletOn(crashed, notifierRef)
        CurrentContext.with(emitted[5]) { sink.provide(6) }
        CurrentContext.with(emitted[6]) { sink.provide(7) }

        // CRASH: host, registry, the live sink instance and the volatile source all vanish.
        val postCrash = SimulationController(seed = 92)
        val recovered = ManagedHost(scheduler = postCrash.scheduler(), journal = journal)
        recovered.managementInlet.call.spawn(NotifierCell(notifierRef, effects))
        postCrash.runToIdle()
        recovered.recoverFrom(journal)
        postCrash.runToIdle()

        // unchanged in size across replay, and unchanged element by element: the seven
        // applied positions and the two retransmitted ones are all at or behind the
        // restored frontier, so all nine replayed frames are suppressed rather than re-acted
        effects shouldBe listOf(1, 2, 3, 4, 5, 6, 7)
        recovered.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 9L

        // ... and it advances for a post-recovery invocation. A *fresh* volatile source,
        // because the pre-crash one is gone: a volatile outlet mints a fresh epoch per
        // incarnation (93 I-14 Rule S1), so this delta is on a lane the frontier has never
        // seen and must not be eaten as already-acted.
        val afterCrash = VolatileSource()
        afterCrash.outlet.subscribe(Use.fixed(inletOn(recovered, notifierRef), PortRef.generate()))
        afterCrash.emit(8)
        postCrash.runToIdle()

        effects shouldBe listOf(1, 2, 3, 4, 5, 6, 7, 8)
    }

    // ------------------------------------------------------------------
    // BS-4 ([CHA2-13] as adjudicated) — the journaled-source construction, unweakened.
    // ------------------------------------------------------------------

    /**
     * One incarnation of BS-4's graph: a journaled source and an `Effectful` sink co-hosted on
     * a durable host over [journal] — the whole-host degenerate tee, so both cells' frames and
     * the sink's frontier advances ride the same WAL. A "crash" is building a fresh instance
     * of this class over the same journal and the same [CellRef]s.
     */
    private class CoHostedGraph(
        controller: SimulationController,
        journal: InMemoryJournal,
        sourceRef: CellRef,
        sinkRef: CellRef,
        effects: MutableList<Int>,
    ) {
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val source = JournaledSource(sourceRef)

        init {
            host.managementInlet.call.spawn(NotifierCell(sinkRef, effects))
            host.managementInlet.call.spawn(source)
            // source → sink through the host intake, so the sink's inlet sees a journaled
            // frame carrying the source outlet's own MessageContext
            val sink = (HostedCellProxy.create(sinkRef, host, SinkProxy::class.java) as SinkProxy).inlet.call
            source.outlet.subscribe(Use.fixed(sink, PortRef.generate()))
        }

        /** Drives the journaled source from outside: a root frame with no wave context of its own. */
        fun feed(n: Int) {
            (HostedCellProxy.create(source.ref, host, SourceProxy::class.java) as SourceProxy)
                .inlet.call.provide(n)
        }
    }

    /**
     * **BS-4 — a journaled source feeding an `Effectful` sink**, written exactly as the
     * feature specifies it: *given* a journaled source cell feeding an `Effectful` sink on the
     * same host, both journaled; *when* the host crashes and recovers, replaying the source's
     * frames; *then* the effect fires at most once per logical invocation.
     *
     * **Observed outcome: PASS**, which is what `computenet-umx.1.1`'s adjudication predicts
     * post-`34892d9` and what `computenet-yh6.1.5`/`[KFX-22]` consumes. The construction is
     * the one `concord/corpus/DISPUTES.md`'s G-59/C-9 boundary entry recorded as double-firing
     * — nothing about it is narrowed, re-seeded or softened, and it carries no
     * `@ExpectedFailure` because the divergence it was filed against is fixed. Had it failed,
     * the required disposition was an `@ExpectedFailure(owner = "computenet-yh6.1")` plus a
     * findings entry and **no** kernel patch (`[CHA2-50]`); it did not.
     *
     * Two things beyond "at most once" are asserted deliberately, because "at most once" alone
     * is satisfied by firing zero times — the effect-**loss** direction that
     * `concord/corpus/DISPUTES.md`'s computenet-61w amendment records as having passed
     * vacuously in `DUR-REPLAY-01` for exactly this reason:
     *
     * - the whole log equals `1..7` after recovery, so each logical invocation fired exactly
     *   once *across* the crash, not merely not-twice;
     * - a post-recovery delta still lands, so the restored identity did not rewind below the
     *   frontier and get silently eaten as already-acted.
     */
    @Test
    fun `BS-4 a journaled source feeding an Effectful sink fires each logical invocation once across a crash`() {
        val journal = InMemoryJournal()
        val effects = mutableListOf<Int>()
        val sourceRef = CellRef(UUID.randomUUID())
        val sinkRef = CellRef(UUID.randomUUID())

        val preCrash = SimulationController(seed = 93)
        val before = CoHostedGraph(preCrash, journal, sourceRef, sinkRef, effects)
        preCrash.runToIdle()

        (1..7).forEach { before.feed(it) }
        preCrash.runToIdle()
        effects shouldBe listOf(1, 2, 3, 4, 5, 6, 7)

        // CRASH: host, registry and both live instances vanish; the journal does not
        val postCrash = SimulationController(seed = 94)
        val after = CoHostedGraph(postCrash, journal, sourceRef, sinkRef, effects)
        postCrash.runToIdle()
        after.host.recoverFrom(journal)
        postCrash.runToIdle()

        // the replayed input frames re-drove the rebuilt source, whose re-emissions carried
        // the identity the sink's restored frontier had recorded — suppressed, not re-acted
        effects shouldBe listOf(1, 2, 3, 4, 5, 6, 7)

        // and the recovered lane is not below the frontier either: a live delta lands
        after.feed(8)
        postCrash.runToIdle()
        effects shouldBe listOf(1, 2, 3, 4, 5, 6, 7, 8)
    }

    // ------------------------------------------------------------------
    // BS-5 ([CHA2-14]) — the decided baseline rule, pinned with its answer.
    // ------------------------------------------------------------------

    /**
     * **BS-5 — a PN-2 replay frame stamped `MessageContext.baseline`, arriving at an
     * `Effectful` inlet at or behind the restored frontier.**
     *
     * `[CHA2-14]` asks the suite to *record whether the frontier check exempts it, and report
     * the answer*. When the feature was written that was an open probe
     * (`link/CatchUp.kt`'s KDoc: the `Effectful` frontier check is "the only counter observer
     * that does not exempt baselines"). It is no longer: `computenet-yh6.1.3.4` decided and
     * implemented `[24-DUR-07]`/`[24-DUR-08]`, so this is a pin of a decided rule.
     *
     * **The recorded answer: no, the frontier check does not exempt a baseline — and that is
     * the decided behaviour, not a divergence.** Rule (3) of the decision is that a PN-2
     * replay-baseline keeps `[24-DUR-05]` verbatim: at or behind the restored frontier
     * suppressed, journal tail fires. What `[24-DUR-07]` changes is the *other* half — a
     * baseline the sink does act on records its exact position in the sink's own discharged
     * -baseline state rather than advancing the wave-position frontier.
     *
     * The evidence that the suppressed frames really were baseline-stamped cannot come from
     * the sink, which never sees them: a suppression happens before delivery, so its handler
     * never runs and no context is observable there. It comes instead from a plain,
     * non-`Effectful` [ContextRecorder] co-hosted on the same journal, driven under the *same*
     * `(sourceId, counter)` as the sink and therefore replayed through the same `recoverFrom`
     * staging — where `HostDurability.baselined` stamps every context-carrying frame. What it
     * records is that positions 1 and 2 arrived on the replay path carrying a baseline, which
     * is precisely the antecedent of `[CHA2-14]`; the sink suppressed them anyway.
     */
    @Test
    fun `BS-5 a PN-2 replay-baseline at or behind the restored frontier is suppressed, not exempted`() {
        val journal = InMemoryJournal()
        val effects = mutableListOf<Int>()
        val recorded = mutableListOf<Pair<Long, Boolean>>()
        val sinkRef = CellRef(UUID.randomUUID())
        val recorderRef = CellRef(UUID.randomUUID())
        val lane = UUID.randomUUID()

        val preCrash = SimulationController(seed = 95)
        val crashed = ManagedHost(scheduler = preCrash.scheduler(), journal = journal)
        crashed.managementInlet.call.spawn(NotifierCell(sinkRef, effects))
        crashed.managementInlet.call.spawn(ContextRecorder(recorderRef, recorded))
        preCrash.runToIdle()

        // two live frames on lane `lane`, applied by the sink and seen by the control
        for (n in 1..2) {
            CurrentContext.with(live(lane, n.toLong())) {
                inletOn(crashed, sinkRef).provide(n)
                inletOn(crashed, recorderRef).provide(n)
            }
        }
        preCrash.runToIdle()

        effects shouldBe listOf(1, 2)
        // live delivery carries no baseline — the stamp below is the replay's, not the driver's
        recorded shouldBe listOf(1L to false, 2L to false)
        recorded.clear()

        // journaled, never delivered: the crash lands between the intake tee and delivery,
        // so position 3 is journal tail the sink never acted on
        CurrentContext.with(live(lane, 3L)) { inletOn(crashed, sinkRef).provide(3) }

        // CRASH
        val postCrash = SimulationController(seed = 96)
        val recovered = ManagedHost(scheduler = postCrash.scheduler(), journal = journal)
        recovered.managementInlet.call.spawn(NotifierCell(sinkRef, effects))
        recovered.managementInlet.call.spawn(ContextRecorder(recorderRef, recorded))
        postCrash.runToIdle()
        recovered.recoverFrom(journal)
        postCrash.runToIdle()

        // ANSWER, half 1 — the antecedent is real: the replayed frames at positions 1 and 2
        // DID arrive carrying a baseline (PN-2's stamp), at or behind the restored frontier.
        recorded shouldBe listOf(1L to true, 2L to true)
        recorded.all { it.second }.shouldBeTrue()

        // ANSWER, half 2 — the frontier check does NOT exempt them: positions 1 and 2 were
        // suppressed at the Effectful inlet despite the baseline stamp, while position 3 —
        // the same stamp, ahead of the frontier — fired. That is [24-DUR-05] kept verbatim
        // under PN-2, which is rule (3) of the [24-DUR-07]/[24-DUR-08] decision.
        effects shouldBe listOf(1, 2, 3)
        recovered.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 2L
    }
}
