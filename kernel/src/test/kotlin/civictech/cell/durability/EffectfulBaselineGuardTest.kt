package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Owned
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.evolve.Effectful
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Catch-up **baselines** at an `Effectful` inlet — `[24-DUR-07]` / `[24-DUR-08]`
 * (spec 24 §Effectful, 93 I-24; KFX-BASELINE, `computenet-yh6.1.3.4`), where
 * `EffectfulRecoveryTest` covers the live-wave frontier and
 * `EffectfulInletGuardTest` covers its refusal/discharge edges.
 *
 * A frame may carry a `MessageContext.baseline`: the I-24 pull baseline that
 * answers a late-joining consumer's `StateRequest`, or PN-2's replay stamp,
 * which marks a replayed frame as catch-up rather than as a live wave. The
 * decided rule is one rule for every `Effectful` cell:
 *
 * 1. The sink **acts** on a baseline — a newly-joined notifier fires for the
 *    state it caught up to, and responds to deltas individually from then on.
 * 2. A baseline's timestamp **never advances the processed-frontier**: it is
 *    anchored at the stamped link-install event, not at a wave position, so a
 *    wave-position high-water taken from it would swallow genuine live frames
 *    from that source sitting below it.
 * 3. PN-2 replay-baselines keep `[24-DUR-05]` exactly: at or behind the restored
 *    frontier suppressed, journal-tail fires.
 *
 * (1) and (2) together would leave a baseline firing with nothing to suppress
 * its own replay, so `[24-DUR-08]` gives the sink its **own** journaled
 * discharge record — separate from the wave frontier, an exact position rather
 * than a high-water — which is what makes a catch-up join survive a crash
 * without re-firing. Nothing here asks anything of producers, ingress or the
 * catch-up protocol.
 *
 * The context shape driven below is exactly what `FanOutlet.baselineTo` stamps:
 * the outlet's own next `(sourceId, counter)` plus the merge-tag frontier as
 * `baseline`.
 */
class EffectfulBaselineGuardTest {

    /** The effect-boundary sink: every `provide` acts on [world] — external, outside instance lifecycle. */
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

    interface NotifierProxy {
        val inlet: Use<Consumer<Int>>
    }

    /** The exclusive-payload variant, for the no-silent-drop half of a suppression. */
    class OwnedSink(override val ref: CellRef, private val world: MutableList<String>) : Cell, Effectful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Owned<String>>>())

        init {
            inlet.serve(object : Consumer<Owned<String>> {
                override fun provide(input: Owned<String>) {
                    world += input.take()
                }
            })
        }
    }

    interface OwnedSinkProxy {
        val inlet: Use<Consumer<Owned<String>>>
    }

    private fun sinkOn(host: ManagedHost, ref: CellRef): Consumer<Int> =
        (HostedCellProxy.create(ref, host, NotifierProxy::class.java) as NotifierProxy).inlet.call

    /** A catch-up baseline emission's context — `FanOutlet.baselineTo`'s exact shape. */
    private fun baseline(source: UUID, counter: Long, anchor: TagFrontier) =
        MessageContext(Timestamp(source, counter), PortRef.generate(), baseline = anchor)

    /** An ordinary live wave frame from the same lane. */
    private fun live(source: UUID, counter: Long) =
        MessageContext(Timestamp(source, counter), PortRef.generate())

    /**
     * `[24-DUR-07]`, both halves, and the arm that fails against the unfixed
     * guard: the guard read `MessageContext.timestamp` alone, so a pull baseline
     * advanced the processed-frontier to the link-install anchor's counter and
     * every live frame from that source below it was silently suppressed as
     * "already acted".
     */
    @Test
    fun `a pull-baseline fires and never advances the processed-frontier (24-DUR-07)`() {
        val controller = SimulationController(seed = 1)
        val world = mutableListOf<Int>()
        val host = ManagedHost(scheduler = controller.scheduler())
        val ref = CellRef(UUID.randomUUID())
        host.managementInlet.call.spawn(NotifierCell(ref, world))
        controller.runToIdle()

        val sink = sinkOn(host, ref)
        val source = UUID.randomUUID()
        val anchor = TagFrontier(mapOf(UUID.randomUUID() to 4L))

        // the late join: a catch-up baseline reply, stamped at the outlet's own
        // position 5 — the sink ACTS on the state it caught up to
        CurrentContext.with(baseline(source, 5L, anchor)) { sink.provide(100) }
        controller.runToIdle()
        world shouldBe listOf(100)

        // live traffic from the same lane, below the baseline's counter: genuine
        // frames the sink has never acted on. They fire — the baseline's
        // timestamp was never a wave position and moved no frontier.
        CurrentContext.with(live(source, 1L)) { sink.provide(1) }
        CurrentContext.with(live(source, 2L)) { sink.provide(2) }
        controller.runToIdle()

        world shouldBe listOf(100, 1, 2)
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 0L

        // and the ordinary frontier still works on that lane: 2 re-delivered is
        // at-or-behind what the live frame advanced, so it is suppressed
        CurrentContext.with(live(source, 2L)) { sink.provide(2) }
        controller.runToIdle()
        world shouldBe listOf(100, 1, 2)
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 1L
    }

    /**
     * `[24-DUR-08]`: the hole rule (2) opens, closed by the sink's own journaled
     * state. The baseline firing advanced no frontier, so only the discharge
     * record stands between a journaled catch-up join and the whole join
     * re-firing after a crash.
     */
    @Test
    fun `a discharged pull-baseline is not re-fired by journal replay (24-DUR-08)`() {
        val controller = SimulationController(seed = 2)
        val journal = InMemoryJournal() // "the disk": the only thing that survives the crash
        val world = mutableListOf<Int>()
        val logicalId = UUID.randomUUID()
        val source = UUID.randomUUID()
        val anchor = TagFrontier(mapOf(UUID.randomUUID() to 9L))

        var host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        controller.runToIdle()

        // a two-frame catch-up burst on join, journaled by the intake tee
        CurrentContext.with(baseline(source, 5L, anchor)) { sinkOn(host, CellRef(logicalId)).provide(100) }
        CurrentContext.with(baseline(source, 6L, anchor)) { sinkOn(host, CellRef(logicalId)).provide(200) }
        controller.runToIdle()
        world shouldBe listOf(100, 200)

        // CRASH: host, registry and the live sink instance vanish; the journal remains
        host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        controller.runToIdle()
        host.recoverFrom(journal)
        controller.runToIdle()

        // fired exactly once across the crash — the catch-up is not replayed onto the world
        world shouldBe listOf(100, 200)
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 2L

        // and the recovered sink still takes live deltas on that lane, including
        // ones below the baseline's counter (the frontier is still untouched)
        CurrentContext.with(live(source, 3L)) { sinkOn(host, CellRef(logicalId)).provide(3) }
        controller.runToIdle()
        world shouldBe listOf(100, 200, 3)
    }

    /**
     * `[24-DUR-08]` across compaction: `checkpoint` truncates the WAL down to its
     * blob, so the discharge record has to be captured there as well — a
     * `RECORD_BASELINE` beside the checkpoint, the same additive shape the
     * outlet-wave record uses. This sink is neither `Stateful` nor holder of any
     * frontier entry, so its discharged baselines are the *only* recoverable
     * content its journal has.
     *
     * The retransmit AFTER the checkpoint is what makes this bite. Compaction
     * truncates the original frame away, so a journal holding only the checkpoint
     * blob replays nothing and would recover cleanly whether or not the discharge
     * survived — the re-emission is observable only against a frame journaled
     * after the reset, which is exactly what an upstream retransmit of the
     * baseline reply produces (suppressed live by the in-memory set, journaled by
     * the intake tee regardless).
     */
    @Test
    fun `checkpoint compaction preserves a discharged baseline (24-DUR-08)`() {
        val controller = SimulationController(seed = 3)
        val journal = InMemoryJournal()
        val world = mutableListOf<Int>()
        val logicalId = UUID.randomUUID()
        val source = UUID.randomUUID()
        val anchor = TagFrontier(mapOf(UUID.randomUUID() to 2L))
        val context = baseline(source, 5L, anchor)

        var host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        controller.runToIdle()

        CurrentContext.with(context) { sinkOn(host, CellRef(logicalId)).provide(100) }
        controller.runToIdle()
        world shouldBe listOf(100)

        host.checkpoint(journal)

        // the same catch-up position again, now journaled on the POST-checkpoint
        // tail: suppressed live, and the compacted discharge is the only thing
        // that will suppress it again on replay
        CurrentContext.with(context) { sinkOn(host, CellRef(logicalId)).provide(100) }
        controller.runToIdle()
        world shouldBe listOf(100)

        host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        controller.runToIdle()
        host.recoverFrom(journal)
        controller.runToIdle()

        world shouldBe listOf(100)
    }

    /**
     * Rule (3): a PN-2 replay-baseline is not a pull baseline and keeps
     * `[24-DUR-05]` verbatim — the frame the sink already acted on is at or
     * behind the restored frontier and is suppressed, while the journal tail it
     * never reached fires. The tail frame arrives baseline-marked (PN-2 stamps
     * the replay), so its firing records a discharge, and a *second* crash does
     * not re-fire it either.
     *
     * The crash lands between the intake tee and delivery: the frame is journaled
     * synchronously at enqueue, and its delivery is a later scheduler task that
     * the pre-crash controller never runs.
     */
    @Test
    fun `a PN-2 replay-baseline keeps 24-DUR-05 - acted suppressed, journal tail fires`() {
        val journal = InMemoryJournal()
        val world = mutableListOf<Int>()
        val logicalId = UUID.randomUUID()
        val source = UUID.randomUUID()

        val preCrash = SimulationController(seed = 4)
        val crashed = ManagedHost(scheduler = preCrash.scheduler(), journal = journal)
        crashed.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        preCrash.runToIdle()

        CurrentContext.with(live(source, 1L)) { sinkOn(crashed, CellRef(logicalId)).provide(1) }
        preCrash.runToIdle()
        world shouldBe listOf(1)

        // journaled, never delivered — the crash lands here, so this frame is
        // journal tail the sink never acted on
        CurrentContext.with(live(source, 2L)) { sinkOn(crashed, CellRef(logicalId)).provide(2) }

        val postCrash = SimulationController(seed = 5)
        var host = ManagedHost(scheduler = postCrash.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        postCrash.runToIdle()
        host.recoverFrom(journal)
        postCrash.runToIdle()

        // frame 1: at the restored frontier, suppressed. frame 2: tail, fires.
        world shouldBe listOf(1, 2)
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 1L

        // SECOND CRASH: the tail frame fired while PN-2-marked, so its discharge
        // is journaled too — replaying the same journal again re-fires nothing
        val afterSecond = SimulationController(seed = 6)
        host = ManagedHost(scheduler = afterSecond.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        afterSecond.runToIdle()
        host.recoverFrom(journal)
        afterSecond.runToIdle()

        world shouldBe listOf(1, 2)
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 2L
    }

    /**
     * `[24-DUR-08]`'s **bound** (`computenet-yh6.1.3.4.2`; spec 24 §Effectful, "Bounding
     * the discharged-baseline set"). The set is exact and a baseline advances no frontier,
     * so the checkpoint-time compaction — drop what the processed-frontier already covers
     * — never fires for a lane that emits baselines and no live frames after them, and the
     * set only grows. It is capped per inlet at `DISCHARGED_BASELINE_CAP` (1024),
     * frontier-covered positions first and then oldest-discharge-first.
     *
     * Three things are pinned here, and the middle one is the point:
     *
     * 1. The bound is real — the oldest baseline position is gone, so a re-delivery of it
     *    **re-fires**. That is the stated loss mode, and it is the arm that fails against
     *    an unbounded set, where the position would still be suppressed.
     * 2. **`[24-DUR-07]` still holds under eviction**: live frames from the same source
     *    below every baseline's counter fire, none of them suppressed. Eviction only ever
     *    shrinks the suppression set, which is exactly why the cap cannot reintroduce the
     *    collateral suppression a per-source high-water would have — the property that
     *    ruled the high-water candidates out.
     * 3. Retention is oldest-first, not arbitrary: the newest position is still suppressed.
     */
    @Test
    fun `the discharged-baseline set is bounded per inlet and 24-DUR-07 survives eviction`() {
        val controller = SimulationController(seed = 8)
        val world = mutableListOf<Int>()
        val host = ManagedHost(scheduler = controller.scheduler())
        val ref = CellRef(UUID.randomUUID())
        host.managementInlet.call.spawn(NotifierCell(ref, world))
        controller.runToIdle()

        val sink = sinkOn(host, ref)
        val source = UUID.randomUUID()
        val anchor = TagFrontier(mapOf(UUID.randomUUID() to 7L))

        // The growth case: a lane that only ever answers with catch-up baselines, so
        // nothing the processed-frontier covers is ever available to compact. Counters
        // start high so genuine live traffic can later sit *below* every one of them.
        val cap = 1024
        val overflow = 5
        val first = 1_000L
        val last = first + cap + overflow - 1
        for (counter in first..last) {
            CurrentContext.with(baseline(source, counter, anchor)) { sink.provide(counter.toInt()) }
        }
        controller.runToIdle()
        world.size shouldBe cap + overflow
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 0L

        // (2) `[24-DUR-07]` under eviction: live frames on the same lane, below every
        // baseline counter, are genuine frames the sink has never acted on. All fire.
        CurrentContext.with(live(source, 1L)) { sink.provide(1) }
        CurrentContext.with(live(source, 2L)) { sink.provide(2) }
        CurrentContext.with(live(source, 3L)) { sink.provide(3) }
        controller.runToIdle()
        world.takeLast(3) shouldBe listOf(1, 2, 3)
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 0L

        // (3) the newest discharge is retained — a retransmit of it is still suppressed
        CurrentContext.with(baseline(source, last, anchor)) { sink.provide(last.toInt()) }
        controller.runToIdle()
        world.takeLast(3) shouldBe listOf(1, 2, 3)
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 1L

        // (1) the oldest was evicted — the same retransmit re-fires the effect. Against an
        // unbounded set this position is still discharged and this arm reads `1, 2, 3`.
        CurrentContext.with(baseline(source, first, anchor)) { sink.provide(first.toInt()) }
        controller.runToIdle()
        world.takeLast(2) shouldBe listOf(3, first.toInt())
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 1L
    }

    /**
     * A `[24-DUR-08]` suppression is a drop, so the AGENTS.md no-silent-drop
     * invariant binds it exactly as KFX-20 binds the `[24-DUR-05]` branch it
     * shares: the re-delivered baseline's `Owned` is consumed by the guard rather
     * than leaked, and the drop is accounted. The later `take()` is a
     * use-after-move error, which is the proof the discharge ran.
     */
    @Test
    fun `a suppressed re-delivered baseline consumes the Owned it carried (24-DUR-08)`() {
        val controller = SimulationController(seed = 7)
        val world = mutableListOf<String>()
        val host = ManagedHost(scheduler = controller.scheduler())
        val ref = CellRef(UUID.randomUUID())
        host.managementInlet.call.spawn(OwnedSink(ref, world))
        controller.runToIdle()

        val sink = (HostedCellProxy.create(ref, host, OwnedSinkProxy::class.java) as OwnedSinkProxy).inlet.call
        val context = baseline(UUID.randomUUID(), 5L, TagFrontier(mapOf(UUID.randomUUID() to 1L)))

        CurrentContext.with(context) { sink.provide(Owned("caught up")) }
        controller.runToIdle()
        world shouldBe listOf("caught up")

        // the same catch-up position again — an upstream retransmit of the
        // baseline reply, or a post-recovery live re-delivery
        val duplicate = Owned("suppressed")
        CurrentContext.with(context) { sink.provide(duplicate) }
        controller.runToIdle()

        world shouldBe listOf("caught up")
        shouldThrow<IllegalStateException> { duplicate.take() }
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 1L
    }
}
