package civictech.cell.evolve

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.gen.wire.Contract
import civictech.gen.wire.Key
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

@Contract
interface ShadowOwnedPush {
    fun push(@Key value: Owned<String>)
}

@Contract
interface ShadowLeasedPush {
    fun push(@Key value: Leased<String>)
}

/** W1.2 / C-11: shadow suppression remains the sole consumer of exclusives. */
class ShadowOwnershipTest {

    private class OwnedSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<ShadowOwnedPush>())
    }

    private class LeasedSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<ShadowLeasedPush>())
    }

    private class ShadowSink(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Effectful {
        val ownedInlet = registerPort("ownedInlet", FanInlet.create<ShadowOwnedPush>())
        val leasedInlet = registerPort("leasedInlet", FanInlet.create<ShadowLeasedPush>())
        var effects = 0

        init {
            ownedInlet.serve(object : ShadowOwnedPush {
                override fun push(value: Owned<String>) {
                    value.take()
                    effects++
                }
            })
            leasedInlet.serve(object : ShadowLeasedPush {
                override fun push(value: Leased<String>) {
                    value.release()
                    effects++
                }
            })
        }
    }

    @Test
    fun `shadow Owned and Leased pipelines discharge exactly once without effects`() {
        val controller = SimulationController(12)
        val host = ManagedHost(scheduler = controller.scheduler())
        val ownedSource = OwnedSource()
        val leasedSource = LeasedSource()
        val sink = ShadowSink()

        host.managementInlet.call.spawn(ownedSource)
        host.managementInlet.call.spawn(leasedSource)
        Shadow.spawn(host, sink)
        controller.runToIdle()
        ownedSource.outlet.subscribe(sink.ownedInlet as Use<ShadowOwnedPush>)
        leasedSource.outlet.subscribe(sink.leasedInlet as Use<ShadowLeasedPush>)

        val owned = Owned("moved")
        var poolBalance = 0
        val leased = Leased("pooled") { poolBalance++ }
        ownedSource.outlet.call.push(owned)
        leasedSource.outlet.call.push(leased)

        sink.effects shouldBe 0
        poolBalance shouldBe 1
        shouldThrow<IllegalStateException> { owned.take() }
        shouldThrow<IllegalStateException> { leased.release() }
    }
}
