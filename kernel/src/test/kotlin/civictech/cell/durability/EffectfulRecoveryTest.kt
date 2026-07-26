package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.evolve.Effectful
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * W2.6 exit (G-59, fixes C-9; spec 20/24 "Boundary of the landed mechanism",
 * 30/31 recovery precedence, 50/52 "Effectful recovery"): an `Effectful` sink
 * journals a durable processed-frontier — per inlet, the last applied
 * `(sourceId, counter)` — so a crashed peer's journal replay does not re-fire
 * an invocation the pre-crash instance already acted on. Un-suppressed replay
 * (the landed M10 behavior, C-9) double-fires; this is the M9.2 double-fire
 * control (`ShadowPromotionTest`'s `control - an unsuppressed shadow sink
 * double-fires`) inverted onto crash recovery: recovery MUST NOT double-fire.
 *
 * The world the sink acts on ([world]) is external to any single cell
 * instance — a crashed instance is discarded entirely, so only an effect
 * sink outside the instance lifecycle can catch a *true* double-fire (the
 * pre-crash instance already acted; the recovered instance must not act
 * again for the same `(sourceId, counter)`).
 */
class EffectfulRecoveryTest {

    class SourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Int>>())
        fun emit(n: Int) = outlet.call.provide(n)
    }

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
    fun `a crashed peer recovers from its journal without re-firing its effectful sink`() {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal() // "the disk": the only thing that survives the crash
        val world = mutableListOf<Int>() // the external effect target, outside any cell instance

        val logicalId = UUID.randomUUID()
        var host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        val source = SourceCell()

        fun routeToSink(targetHost: ManagedHost): Consumer<Int> =
            (HostedCellProxy.create(CellRef(logicalId), targetHost, NotifierProxy::class.java)
                    as NotifierProxy).inlet.call

        var currentLink: PortRef? = null
        fun rewire(targetHost: ManagedHost) {
            currentLink?.let { source.outlet.unsubscribe(it) }
            val ref = PortRef.generate()
            source.outlet.subscribe(Use.fixed(routeToSink(targetHost), ref))
            currentLink = ref
        }

        rewire(host)
        controller.runToIdle()

        // pre-crash traffic: accepted, journaled, AND already acted on the world
        source.emit(1)
        source.emit(2)
        source.emit(3)
        controller.runToIdle()
        world shouldBe listOf(1, 2, 3)

        // CRASH: host, registry, and the live sink instance vanish — only the journal survives
        host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        controller.runToIdle()
        host.recoverFrom(journal) // journal replay must NOT re-act on what already acted pre-crash
        controller.runToIdle()

        // no double-fire: the pre-crash effects already landed on `world`; replay must dedupe them
        world shouldBe listOf(1, 2, 3)

        // post-recovery live traffic still reaches the recovered sink normally
        rewire(host)
        source.emit(4)
        controller.runToIdle()

        world shouldBe listOf(1, 2, 3, 4)
    }

    @Test
    fun `checkpoint compaction preserves the processed-frontier across recovery`() {
        val controller = SimulationController(seed = 2)
        val journal = InMemoryJournal()
        val world = mutableListOf<Int>()

        val logicalId = UUID.randomUUID()
        var host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        val source = SourceCell()

        fun routeToSink(targetHost: ManagedHost): Consumer<Int> =
            (HostedCellProxy.create(CellRef(logicalId), targetHost, NotifierProxy::class.java)
                    as NotifierProxy).inlet.call

        var currentLink: PortRef? = null
        fun rewire(targetHost: ManagedHost) {
            currentLink?.let { source.outlet.unsubscribe(it) }
            val ref = PortRef.generate()
            source.outlet.subscribe(Use.fixed(routeToSink(targetHost), ref))
            currentLink = ref
        }

        rewire(host)
        controller.runToIdle()

        // acted on the world, THEN compacted: the checkpoint blob is the only
        // journal content left — it must carry the frontier along with state
        source.emit(1)
        source.emit(2)
        controller.runToIdle()
        host.checkpoint(journal)

        // more traffic after the checkpoint — still in the journal tail
        source.emit(3)
        controller.runToIdle()
        world shouldBe listOf(1, 2, 3)

        // CRASH
        host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(NotifierCell(CellRef(logicalId), world))
        controller.runToIdle()
        host.recoverFrom(journal)
        controller.runToIdle()

        // no double-fire for either the checkpointed prefix or the tail
        world shouldBe listOf(1, 2, 3)

        rewire(host)
        source.emit(4)
        controller.runToIdle()
        world shouldBe listOf(1, 2, 3, 4)
    }
}
