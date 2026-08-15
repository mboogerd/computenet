package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.Leased
import civictech.cell.MessageContext
import civictech.cell.Owned
import civictech.cell.Timestamp
import civictech.cell.evolve.Effectful
import civictech.cell.host.ActorIngress
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
 * The `Effectful` processed-frontier guard (`ManagedHost.deliver`, PORT_API
 * branch; G-59, fixes C-9) on its *edges*, where `EffectfulRecoveryTest` covers
 * its happy path.
 *
 * Two edges, from KFX feature `computenet-yh6.1.3`:
 *
 * - **KFX-20 / BS-42** — a suppression is a drop. An invocation suppressed as
 *   already-acted never reaches the sink, so nothing downstream will ever
 *   consume the `Owned`/`Leased` it carries. The guard must discharge those
 *   explicitly (spec 23 §Ownership; the AGENTS.md no-silent-drop invariant) and
 *   the discharge must be observable — `SupervisionAccounting`, not a comment.
 *
 * - **KFX-16 / BS-30**, now `[24-DUR-06]` — a frame carrying no `MessageContext`
 *   has no frontier position at all, so `[24-DUR-05]`'s antecedent could not be
 *   evaluated for it and its effect re-fired on replay. That was recorded as a
 *   bounded limit in `concord/corpus/DISPUTES.md` and has since been **closed by
 *   construction rather than by patching**: an `Effectful` cell is not directly
 *   manipulable by a caller that cannot supply frontier information, so a
 *   contextless `PORT_API` invocation is **undeliverable** at an `Effectful`
 *   inlet — refused, its exclusives discharged, the refusal accounted. Past that
 *   refusal every frame the sink acts on has a position, which is what makes
 *   `[24-DUR-05]` hold unconditionally as written. Direct drivers stamp their own
 *   actor lane through [civictech.cell.host.ActorIngress]; minting and persisting
 *   that actor identity is the connector ingress's job (CON1), not the kernel's.
 */
class EffectfulInletGuardTest {

    /** Effect-boundary sink over an exclusive payload: every accepted `provide` consumes the transfer. */
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

    /** The same, for a lease obligation: every accepted `provide` releases it back to the pool. */
    class LeasedSink(override val ref: CellRef, private val world: MutableList<String>) : Cell, Effectful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Leased<String>>>())

        init {
            inlet.serve(object : Consumer<Leased<String>> {
                override fun provide(input: Leased<String>) {
                    world += input.value
                    input.release()
                }
            })
        }
    }

    interface LeasedSinkProxy {
        val inlet: Use<Consumer<Leased<String>>>
    }

    /** Effect-boundary sink over a plain (journalable) payload — the KFX-16 subject. */
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

    /**
     * The same shape *without* the `Effectful` marker — the control for
     * `[24-DUR-06]`'s scope: a contextless drive of an ordinary cell is
     * legitimate and stays admitted.
     */
    class PlainNotifier(override val ref: CellRef, private val seen: MutableList<Int>) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    seen += input
                }
            })
        }
    }

    /**
     * A second delivery of the same `(sourceId, counter)` is behind the frontier
     * the first advanced, so the sink does not act again. The transfer it
     * carried is consumed by the guard rather than leaked: a later `take()` is a
     * use-after-move error, which is exactly the proof that the discharge ran.
     */
    @Test
    fun `a suppressed already-acted invocation consumes the Owned it carried (KFX-20)`() {
        val controller = SimulationController(seed = 1)
        val world = mutableListOf<String>()
        val host = ManagedHost(scheduler = controller.scheduler())
        val ref = CellRef(UUID.randomUUID())
        host.managementInlet.call.spawn(OwnedSink(ref, world))
        controller.runToIdle()

        val sink = (HostedCellProxy.create(ref, host, OwnedSinkProxy::class.java) as OwnedSinkProxy).inlet.call
        // one wave position, delivered twice — the shape a post-recovery live
        // re-delivery or an upstream retransmit presents at the inlet
        val context = MessageContext(Timestamp(UUID.randomUUID(), 7L), PortRef.generate())

        CurrentContext.with(context) { sink.provide(Owned("acted")) }
        controller.runToIdle()
        world shouldBe listOf("acted")

        val duplicate = Owned("suppressed")
        CurrentContext.with(context) { sink.provide(duplicate) }
        controller.runToIdle()

        // suppressed: the sink did not act a second time...
        world shouldBe listOf("acted")
        // ...and the exclusive it carried was consumed, not dropped on the floor
        shouldThrow<IllegalStateException> { duplicate.take() }
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 1L
    }

    /** The lease half of BS-42: a suppressed `Leased` returns to its pool. */
    @Test
    fun `a suppressed already-acted invocation releases the Leased it carried (KFX-20)`() {
        val controller = SimulationController(seed = 2)
        val world = mutableListOf<String>()
        val returned = mutableListOf<String>()
        val host = ManagedHost(scheduler = controller.scheduler())
        val ref = CellRef(UUID.randomUUID())
        host.managementInlet.call.spawn(LeasedSink(ref, world))
        controller.runToIdle()

        val sink = (HostedCellProxy.create(ref, host, LeasedSinkProxy::class.java) as LeasedSinkProxy).inlet.call
        val context = MessageContext(Timestamp(UUID.randomUUID(), 3L), PortRef.generate())

        CurrentContext.with(context) { sink.provide(Leased("acted") { returned += it }) }
        controller.runToIdle()
        world shouldBe listOf("acted")
        returned shouldBe listOf("acted")

        val duplicate = Leased("suppressed") { returned += it }
        CurrentContext.with(context) { sink.provide(duplicate) }
        controller.runToIdle()

        world shouldBe listOf("acted")
        // the lease obligation was discharged by the guard, exactly once
        returned shouldBe listOf("acted", "suppressed")
        shouldThrow<IllegalStateException> { duplicate.release() }
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 1L
    }

    /**
     * A suppression discharges whatever the invocation carried regardless of
     * where the traffic came from — replayed frames, post-recovery live
     * re-deliveries and a journaled source's replayed emissions all pass
     * through this one branch, so repeated suppressions each discharge.
     */
    @Test
    fun `every suppression discharges - the guard does not special-case traffic origin (KFX-20)`() {
        val controller = SimulationController(seed = 3)
        val world = mutableListOf<String>()
        val host = ManagedHost(scheduler = controller.scheduler())
        val ref = CellRef(UUID.randomUUID())
        host.managementInlet.call.spawn(OwnedSink(ref, world))
        controller.runToIdle()

        val sink = (HostedCellProxy.create(ref, host, OwnedSinkProxy::class.java) as OwnedSinkProxy).inlet.call
        val source = UUID.randomUUID()
        val ahead = MessageContext(Timestamp(source, 1L), PortRef.generate())
        CurrentContext.with(ahead) { sink.provide(Owned("acted")) }
        controller.runToIdle()

        // three deliveries at or behind the frontier, from the same source
        val duplicates = listOf(
            Timestamp(source, 0L) to Owned("behind"),
            Timestamp(source, 1L) to Owned("at"),
            Timestamp(source, 1L) to Owned("at again"),
        )
        duplicates.forEach { (timestamp, payload) ->
            CurrentContext.with(MessageContext(timestamp, PortRef.generate())) { sink.provide(payload) }
        }
        controller.runToIdle()

        world shouldBe listOf("acted")
        duplicates.forEach { (_, payload) -> shouldThrow<IllegalStateException> { payload.take() } }
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 3L

        // a frame ahead of the frontier still fires and advances it
        CurrentContext.with(MessageContext(Timestamp(source, 2L), PortRef.generate())) { sink.provide(Owned("next")) }
        controller.runToIdle()
        world shouldBe listOf("acted", "next")
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 3L
    }

    /**
     * BS-30 / `[24-DUR-06]`, the inversion of what this test asserted while
     * KFX-16 stood as a recorded limit (it used to end `world shouldBe
     * listOf(1, 1)` — the effect re-firing on replay).
     *
     * The same externally-driven root shape: an `Effectful` cell driven directly
     * by an outside caller with no `MessageContext`, hence no `(sourceId,
     * counter)` position on the inlet's processed-frontier. It is now **refused**
     * — the sink never acts, the frontier is never advanced, the refusal is
     * accounted and dead-lettered. The same driver going through a stamped
     * [ActorIngress] is admitted and fires **exactly once across the crash**,
     * which is `[24-DUR-05]` honoured for traffic the kernel previously could not
     * evaluate it for.
     *
     * The refusal is what makes the guard unconditional: past it, there is no
     * frame at an `Effectful` inlet without a position to compare.
     */
    @Test
    fun `a contextless external drive is refused and the stamped path fires exactly once across replay (KFX-16, 24-DUR-06)`() {
        val controller = SimulationController(seed = 4)
        val journal = InMemoryJournal() // the only thing that survives the crash
        val world = mutableListOf<Int>()
        val logicalId = UUID.randomUUID()

        var host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        controller.runToIdle()

        fun sinkOn(target: ManagedHost) =
            (HostedCellProxy.create(CellRef(logicalId), target, NotifierProxy::class.java) as NotifierProxy).inlet.call

        // NO CurrentContext: the externally-driven root case — HostedCellProxy
        // stamps `CurrentContext.get()`, which is null off the data path
        sinkOn(host).provide(99)
        controller.runToIdle()

        // undeliverable: the sink never acted on it, and the denial is observable
        world shouldBe emptyList()
        host.supervisionAccounting().effectfulContextlessRefusals shouldBe 1L
        host.supervisionAccounting().deadLetters shouldBe 1L

        // the same driver, plugged in as an actor carrying its own frontier lane
        val actor = ActorIngress(UUID.randomUUID())
        actor.drive { sinkOn(host).provide(1) }
        controller.runToIdle()
        world shouldBe listOf(1)

        // CRASH: host, registry and the live sink instance vanish; the journal remains
        host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        controller.runToIdle()
        host.recoverFrom(journal)
        controller.runToIdle()

        // fired exactly once across the crash: the stamped frame's position was
        // journaled with it and the frontier advance beside it, so replay dedups.
        world shouldBe listOf(1)
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 1L
        // the refused frame was journaled before it was refused, so replay meets
        // it again — and refuses it again. The refusal is idempotent, not a
        // one-shot admission check.
        host.supervisionAccounting().effectfulContextlessRefusals shouldBe 1L
    }

    /**
     * `[24-DUR-06]` is a *failure path*, so the AGENTS.md no-silent-drop
     * invariant binds it exactly as KFX-20 binds the suppression branch: a
     * refused invocation's `Owned` is consumed rather than leaked, and the
     * refusal is counted. The later `take()` is a use-after-move error, which is
     * the proof the discharge ran rather than the payload merely vanishing.
     */
    @Test
    fun `a refused contextless invocation consumes the Owned it carried (24-DUR-06)`() {
        val controller = SimulationController(seed = 6)
        val world = mutableListOf<String>()
        val host = ManagedHost(scheduler = controller.scheduler())
        val ref = CellRef(UUID.randomUUID())
        host.managementInlet.call.spawn(OwnedSink(ref, world))
        controller.runToIdle()

        val sink = (HostedCellProxy.create(ref, host, OwnedSinkProxy::class.java) as OwnedSinkProxy).inlet.call
        val refused = Owned("refused")
        sink.provide(refused) // no CurrentContext
        controller.runToIdle()

        world shouldBe emptyList()
        shouldThrow<IllegalStateException> { refused.take() }
        host.supervisionAccounting().effectfulContextlessRefusals shouldBe 1L

        // and the stamped path through the very same proxy is admitted
        ActorIngress(UUID.randomUUID()).drive { sink.provide(Owned("acted")) }
        controller.runToIdle()
        world shouldBe listOf("acted")
        host.supervisionAccounting().effectfulContextlessRefusals shouldBe 1L
    }

    /** The lease half of `[24-DUR-06]`: a refused `Leased` returns to its pool exactly once. */
    @Test
    fun `a refused contextless invocation releases the Leased it carried (24-DUR-06)`() {
        val controller = SimulationController(seed = 7)
        val world = mutableListOf<String>()
        val returned = mutableListOf<String>()
        val host = ManagedHost(scheduler = controller.scheduler())
        val ref = CellRef(UUID.randomUUID())
        host.managementInlet.call.spawn(LeasedSink(ref, world))
        controller.runToIdle()

        val sink = (HostedCellProxy.create(ref, host, LeasedSinkProxy::class.java) as LeasedSinkProxy).inlet.call
        val refused = Leased("refused") { returned += it }
        sink.provide(refused) // no CurrentContext
        controller.runToIdle()

        world shouldBe emptyList()
        returned shouldBe listOf("refused")
        shouldThrow<IllegalStateException> { refused.release() }
        host.supervisionAccounting().effectfulContextlessRefusals shouldBe 1L
    }

    /**
     * The refusal is scoped to `Effectful` inlets and to the data plane. A
     * non-`Effectful` cell driven with no context is untouched — spontaneous
     * calls into ordinary cells are legitimate and remain so — and management
     * traffic (`spawn`, above, in every test here) never enters this branch at
     * all.
     */
    @Test
    fun `a contextless drive of a non-Effectful cell is unaffected (24-DUR-06 scope)`() {
        val controller = SimulationController(seed = 8)
        val seen = mutableListOf<Int>()
        val host = ManagedHost(scheduler = controller.scheduler())
        val ref = CellRef(UUID.randomUUID())
        host.managementInlet.call.spawn(PlainNotifier(ref, seen))
        controller.runToIdle()

        val sink = (HostedCellProxy.create(ref, host, NotifierProxy::class.java) as NotifierProxy).inlet.call
        sink.provide(1)
        controller.runToIdle()

        seen shouldBe listOf(1)
        host.supervisionAccounting().effectfulContextlessRefusals shouldBe 0L
        host.supervisionAccounting().deadLetters shouldBe 0L
    }

    /**
     * The boundary of the KFX-16 limit, added at task review because the
     * recorded argument for keeping it turned on an untested claim.
     *
     * The *same* externally-driven shape, differing only in that the caller
     * supplies a `MessageContext` — a per-call `sourceId` minted fresh, exactly
     * what a naive "stamp at ingress" would produce — **is** deduped across the
     * crash. The stamp rides the journaled frame and the frontier advance is
     * journaled alongside it (`FrontierRecord`), so the restored frontier
     * recognises the replayed frame even though nothing about the id was
     * crash-stable.
     *
     * So the rule `[24-DUR-06]` states is precisely *"a frame with no frontier
     * position is refused"* and not *"a frame from outside is refused"* — this
     * test is the boundary that keeps the two from being conflated, and it stays
     * green unchanged across that closure.
     *
     * It also fixes what a caller pays for choosing badly. A per-call minted id
     * IS admitted and IS correct across replay; what is wrong with it is that it
     * opens one `(sourceId → counter)` lane, and one journaled `FrontierRecord`,
     * per call — never collapsible. That is why [ActorIngress] takes a *stable*
     * actor id and counts within it, and why minting/persisting that id belongs
     * to the connector ingress (CON1) rather than to each caller.
     */
    @Test
    fun `the same external drive carrying a per-call minted context IS deduped across replay (KFX-16 limit boundary)`() {
        val controller = SimulationController(seed = 5)
        val journal = InMemoryJournal()
        val world = mutableListOf<Int>()
        val logicalId = UUID.randomUUID()

        var host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        controller.runToIdle()

        fun driveStamped(target: ManagedHost, value: Int) {
            val sink = (HostedCellProxy.create(CellRef(logicalId), target, NotifierProxy::class.java)
                    as NotifierProxy).inlet.call
            // a wave position minted per call — nothing here survives a crash
            CurrentContext.with(MessageContext(Timestamp(UUID.randomUUID(), 0L), PortRef.generate())) {
                sink.provide(value)
            }
        }

        driveStamped(host, 1)
        controller.runToIdle()
        world shouldBe listOf(1)

        // CRASH: only the journal survives
        host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        controller.runToIdle()
        host.recoverFrom(journal)
        controller.runToIdle()

        // fired exactly once across the crash — [24-DUR-05] honoured
        world shouldBe listOf(1)
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 1L
    }
}
