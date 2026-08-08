package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.evolve.Effectful
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * KFX feature 3 (`computenet-yh6.1.3`), [KFX-05]/BS-11: `[24-DUR-05]`
 * (`doc/spec/20-dataflow-semantics/24-data-cells.md:815-825`) names two paths
 * through the `Effectful` processed-frontier guard at
 * `ManagedHost.kt:785-815` — "whether encountered during `recoverFrom` replay
 * or post-recovery live delivery." `EffectfulRecoveryTest` exercises only the
 * replay half (`DUR-REPLAY-01`). This test exercises the live half: after a
 * crash and `recoverFrom`, a LIVE (not replayed) invocation delivered at or
 * behind the restored frontier must be suppressed by the exact same rule
 * replay uses, and one ahead of the frontier must fire and advance it.
 *
 * This is coverage only — the guard already runs unconditionally on every
 * `PORT_API` delivery, replay or live (`hostedInvocation.invocation.context`
 * carries a `Timestamp` regardless of how the invocation was enqueued), so
 * the behaviour is expected to hold on current code. No production file is
 * touched here.
 *
 * Frontier positions are driven explicitly rather than through a live outlet
 * link: [HostedCellProxy] stamps `CurrentContext.get()` into each
 * `Invocation` (`HostedCellProxy.kt:111`), so wrapping a direct proxy call in
 * `CurrentContext.with(MessageContext(...))` simulates exactly the
 * `(sourceId, counter)` position a real upstream link would have stamped.
 */
class EffectfulLiveDeliveryTest {

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

    @Test
    fun `post-recovery live delivery obeys the restored frontier, same rule as replay`() {
        val controller = SimulationController(seed = 4)
        val journal = InMemoryJournal() // "the disk": the only thing that survives the crash
        val world = mutableListOf<Int>() // the external effect target, outside any cell instance
        val letters = mutableListOf<DeadLetter>()

        fun watchDeadLetters(targetHost: ManagedHost) {
            targetHost.deadLetterOutlet.subscribe(
                Use.fixed(
                    object : Propagate<DeadLetter> {
                        override fun propagate(value: DeadLetter) {
                            letters += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
        }

        fun proxyFor(targetHost: ManagedHost, logicalId: UUID): NotifierProxy =
            HostedCellProxy.create(CellRef(logicalId), targetHost, NotifierProxy::class.java) as NotifierProxy

        val logicalId = UUID.randomUUID()
        val sourceId = UUID.randomUUID() // one fixed source: the frontier is keyed per (cellRef, port, sourceId)
        val sourcePort = PortRef.generate()

        fun deliver(targetHost: ManagedHost, counter: Long, n: Int) =
            CurrentContext.with(MessageContext(Timestamp(sourceId, counter), sourcePort)) {
                proxyFor(targetHost, logicalId).inlet.call.provide(n)
            }

        var host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        controller.runToIdle()
        watchDeadLetters(host)

        // pre-crash traffic: accepted, journaled, AND already acted on the world.
        // These are live deliveries too (not replay) — establishing the frontier
        // this test's post-recovery LIVE deliveries will be checked against.
        deliver(host, 1, 1)
        deliver(host, 2, 2)
        deliver(host, 3, 3)
        controller.runToIdle()
        world shouldBe listOf(1, 2, 3)

        // CRASH: host, registry, and the live sink instance vanish — only the journal survives
        host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        controller.runToIdle()
        host.recoverFrom(journal) // journal replay restores the (sourceId -> counter) frontier
        controller.runToIdle()
        world shouldBe listOf(1, 2, 3)
        watchDeadLetters(host)

        // LIVE duplicate AT the restored frontier (counter 3, same as the last
        // applied counter): suppressed as already-acted, exactly as replay would.
        deliver(host, 3, 99)
        controller.runToIdle()
        world shouldBe listOf(1, 2, 3)

        // LIVE duplicate BEHIND the restored frontier (counter 2 < 3): suppressed too.
        deliver(host, 2, 98)
        controller.runToIdle()
        world shouldBe listOf(1, 2, 3)

        // LIVE delivery AHEAD of the frontier (counter 4): fires once and advances it.
        deliver(host, 4, 4)
        controller.runToIdle()
        world shouldBe listOf(1, 2, 3, 4)

        // Re-deliver counter 4 LIVE again: now suppressed — proving the prior live
        // fire genuinely ADVANCED the frontier, not merely dodged a stale check.
        deliver(host, 4, 97)
        controller.runToIdle()
        world shouldBe listOf(1, 2, 3, 4)

        // No exclusive/ordinary payload was ever routed to a dead letter across
        // any of the suppressed live deliveries above.
        letters shouldBe emptyList()
    }
}
