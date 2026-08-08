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
 * - **KFX-16 / BS-30** — a frame carrying no `MessageContext` has no frontier
 *   position, so `[24-DUR-05]`'s dedup cannot apply to it and its effect
 *   re-fires on replay. The decision recorded for this feature is the third of
 *   the three admissible options: keep the behaviour and write the limit down
 *   (`concord/corpus/DISPUTES.md`, against `24-DUR-05`, under the 93 I-7
 *   external-idempotency ceiling) rather than fabricate wave identity at every
 *   externally-driven ingress or refuse legitimate live traffic. This test is
 *   the assertion that makes the limit a *decided* behaviour rather than an
 *   unexamined one — it is deliberately NOT mirrored by a corpus scenario
 *   (KFX-17: a scenario asserting the re-fire would state a weaker rule than
 *   `[24-DUR-05]` as though it were the decided one).
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
     * BS-30, the decided KFX-16 behaviour. An `Effectful` cell driven directly by
     * an external caller accepts frames with no `MessageContext` — no
     * `(sourceId, counter)`, so no position on the inlet's processed-frontier.
     * `[24-DUR-05]` is written unconditionally, but it can only be honoured
     * where a frontier position exists; the recorded limit is that these frames
     * re-fire on replay. The frontier is not merely un-consulted here — it is
     * never advanced either, which is why the guard never engages (0 suppressions).
     *
     * The limit is written down in `concord/corpus/DISPUTES.md` against
     * `24-DUR-05`; closing it needs a crash-stable ingress identity, filed as
     * `computenet-yh6.1.3.5`. If that lands, this test inverts to `listOf(1)`.
     */
    @Test
    fun `an externally driven effectful frame carries no frontier position and re-fires on replay (KFX-16 recorded limit)`() {
        val controller = SimulationController(seed = 4)
        val journal = InMemoryJournal() // the only thing that survives the crash
        val world = mutableListOf<Int>()
        val logicalId = UUID.randomUUID()

        var host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        controller.runToIdle()

        fun driveDirectly(target: ManagedHost, value: Int) {
            val sink = (HostedCellProxy.create(CellRef(logicalId), target, NotifierProxy::class.java)
                    as NotifierProxy).inlet.call
            // NO CurrentContext: the externally-driven root case — HostedCellProxy
            // stamps `CurrentContext.get()`, which is null off the data path
            sink.provide(value)
        }

        driveDirectly(host, 1)
        controller.runToIdle()
        world shouldBe listOf(1)

        // CRASH: host, registry and the live sink instance vanish; the journal remains
        host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        controller.runToIdle()
        host.recoverFrom(journal)
        controller.runToIdle()

        // The recorded limit, asserted rather than assumed: the replayed frame
        // has no frontier position, so it is not suppressed and the effect fires
        // a second time.
        world shouldBe listOf(1, 1)
        // and the guard never engaged — there was nothing to compare against
        host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 0L
    }
}
