package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.registerPort
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [ManagedHost.outletAt] — the Observe-role attachment seam added for the
 * inspector's flow feed (97-inspector-plan M3). It is an accessor, not a new
 * capability: it hands back only the [civictech.cell.port.FanOutlet] any
 * caller holding the cell object could already reach through
 * [civictech.cell.port.PortRegistry], resolved by ref rather than by name.
 *
 * The three answers it can give are all load-bearing for a flow feed, so each
 * is pinned here: the outlet itself, and the two distinct nulls — "not an
 * emission point" (a delegating pass-through, which a flow feed must report as
 * fused rather than as silent) and "not hosted here".
 */
class OutletAtTest {

    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)

    private interface RelayApi {
        val relay: civictech.cell.port.Use<Propagate<String>>
    }

    private class Relay(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, RelayApi {
        override val relay = registerPort("relay", FanInlet.create<Propagate<String>>())
    }

    @Test
    fun `resolves a hosted cell's outlet by port ref`() {
        val cell = SetCell<String>().also { host.managementInlet.call.spawn(it) }

        host.outletAt(cell.outlet.ref) shouldBe cell.outlet
    }

    @Test
    fun `a registered port that is not an outlet resolves to null`() {
        // an inlet is a real, locally hosted port — but it has no emission
        // point of its own, which is exactly what a flow feed reads as "fused"
        val relay = Relay().also { host.managementInlet.call.spawn(it) }

        host.outletAt(relay.relay.ref).shouldBeNull()
    }

    @Test
    fun `a cell this host does not run resolves to null`() {
        val elsewhere = ManagedHost(registry = LocationRegistry())
        val cell = SetCell<String>().also { elsewhere.managementInlet.call.spawn(it) }

        host.outletAt(cell.outlet.ref).shouldBeNull()
    }

    @Test
    fun `a port ref naming no cell at all resolves to null`() {
        host.outletAt(PortRef.generate()).shouldBeNull()
    }
}
