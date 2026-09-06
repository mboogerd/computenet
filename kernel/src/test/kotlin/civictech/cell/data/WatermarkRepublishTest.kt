package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WatermarkDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.replication.Replication
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `computenet-9sm.2.1`: [WatermarkCell.republish] — the heartbeat mechanism
 * ([42-WM-06], authored by 9sm.1). Uses the single-peer rig idiom of
 * [civictech.cell.replication.StabilityAdvanceTest]: one real replicated
 * [SetCell] on a [ManagedHost], the companion [WatermarkCell] read off
 * [Replication.watermarkOf], its own slot derived via [WatermarkCell.slotId]
 * over [Replication.watermarkRef].
 */
class WatermarkRepublishTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    /** One peer, one replicated [SetCell]. */
    private class Rig(adds: Int = 3) {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val replication = Replication(registry)
        val logicalId: UUID = UUID.randomUUID()
        val cell = SetCell<String>(CellRef(logicalId, 0))

        val companion: WatermarkCell = run {
            replication.replicate(cell, host)
            controller.runToIdle()
            if (adds > 0) {
                @Suppress("UNCHECKED_CAST")
                val ops = (HostedCellProxy.create(cell.ref, registry, SetInletProxy::class.java)
                    as SetInletProxy).inlet.call
                repeat(adds) { i ->
                    ops.add("e$i")
                    controller.runToIdle()
                }
            }
            replication.watermarkOf(logicalId)!!
        }

        val ownSlot: UUID = WatermarkCell.slotId(replication.watermarkRef(cell.ref))

        /** Tap the companion outlet, collecting every [WatermarkDelta] it emits from here on. */
        fun tapOutlet(): MutableList<WatermarkDelta> = mutableListOf<WatermarkDelta>().also { captured ->
            companion.outlet.tap(
                Use.fixed(Propagate<WatermarkDelta> { captured += it }, PortRef.generate())
            )
        }
    }

    @Test
    fun `KE3-11 republish emits the own row verbatim and is idempotent`() {
        val rig = Rig(adds = 3)
        val ownRowBefore = rig.companion.rows().getValue(rig.ownSlot)
        val closedBefore = rig.companion.closed()
        val suspendedBefore = rig.companion.suspended()
        val membersBefore = rig.companion.members()

        val captured = rig.tapOutlet()

        rig.companion.republish()
        rig.companion.republish()
        rig.controller.runToIdle()

        captured.size shouldBe 2
        val expected = WatermarkDelta(rows = mapOf(rig.ownSlot to ownRowBefore))
        captured[0] shouldBe expected
        captured[1] shouldBe expected

        // No lattice was moved: republishing an unchanged row changes nothing.
        rig.companion.rows() shouldBe mapOf(rig.ownSlot to ownRowBefore)
        rig.companion.closed() shouldBe closedBefore
        rig.companion.suspended() shouldBe suspendedBefore
        rig.companion.members() shouldBe membersBefore
    }

    @Test
    fun `KE3-11 a never-advanced row emits nothing`() {
        val rig = Rig(adds = 0)
        rig.companion.rows()[rig.ownSlot] shouldBe null

        val captured = rig.tapOutlet()
        rig.companion.republish()
        rig.controller.runToIdle()

        captured.shouldBeEmpty()
    }

    @Test
    fun `9sm2-D6 a closed slot emits nothing`() {
        val rig = Rig(adds = 3)
        rig.companion.close()
        rig.controller.runToIdle()

        val captured = rig.tapOutlet()
        rig.companion.republish()
        rig.controller.runToIdle()

        captured.shouldBeEmpty()
    }

    @Test
    fun `KE3-12 a republished row is a receiver fixpoint, and a raising delta is not`() {
        val sender = Rig(adds = 3)
        val receiver = Rig(adds = 0)
        val ownRow = sender.companion.rows().getValue(sender.ownSlot)

        // Non-vacuity: a RAISING delta for the same slot IS re-emitted, so the tap is live.
        val raisingCaptured = receiver.tapOutlet()
        receiver.companion.deltaInlet.call.propagate(
            WatermarkDelta(rows = mapOf(sender.ownSlot to ownRow))
        )
        receiver.controller.runToIdle()
        raisingCaptured.size shouldBe 1

        val rowsBefore = receiver.companion.rows()
        val closedBefore = receiver.companion.closed()
        val suspendedBefore = receiver.companion.suspended()
        val membersBefore = receiver.companion.members()

        // Republishing the SAME (unchanged) row is a fixpoint: zero re-emissions,
        // every lane byte-identical.
        val captured = receiver.tapOutlet()
        receiver.companion.deltaInlet.call.propagate(
            WatermarkDelta(rows = mapOf(sender.ownSlot to ownRow))
        )
        receiver.companion.deltaInlet.call.propagate(
            WatermarkDelta(rows = mapOf(sender.ownSlot to ownRow))
        )
        receiver.controller.runToIdle()

        captured.shouldBeEmpty()
        receiver.companion.rows() shouldBe rowsBefore
        receiver.companion.closed() shouldBe closedBefore
        receiver.companion.suspended() shouldBe suspendedBefore
        receiver.companion.members() shouldBe membersBefore
    }

    @Test
    fun `KE3-14 republish mints its own detached wave rather than welding onto an ambient data wave`() {
        val rig = Rig(adds = 0)
        var recordedSourceId: UUID? = null

        rig.companion.outlet.tap(
            Use.fixed(Propagate<WatermarkDelta> {
                recordedSourceId = CurrentContext.get()!!.timestamp.sourceId
            }, PortRef.generate())
        )

        // Call republish() from INSIDE a tap on the data cell's outlet, so a data
        // wave's CurrentContext is on the stack when republish() runs.
        rig.cell.outlet.tap(
            Use.fixed(Propagate<SetDelta<String>> { rig.companion.republish() }, PortRef.generate())
        )

        @Suppress("UNCHECKED_CAST")
        val ops = (HostedCellProxy.create(rig.cell.ref, rig.registry, SetInletProxy::class.java)
            as SetInletProxy).inlet.call
        ops.add("e0")
        rig.controller.runToIdle()

        @Suppress("UNCHECKED_CAST")
        val companionOutlet = rig.companion.outlet as FanOutlet<Propagate<WatermarkDelta>>
        @Suppress("UNCHECKED_CAST")
        val dataOutlet = rig.cell.outlet as FanOutlet<Propagate<SetDelta<String>>>

        recordedSourceId shouldBe companionOutlet.waveState().sourceId
        (recordedSourceId == dataOutlet.waveState().sourceId) shouldBe false
    }
}
