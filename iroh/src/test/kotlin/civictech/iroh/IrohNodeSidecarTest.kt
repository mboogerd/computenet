package civictech.iroh

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The half of [IrohTransport.node]'s first acceptance clause
 * (`computenet-ktn1l.1`) that [IrohNodeTest] cannot exercise: every test there
 * drives a [FakeSidecar] directly, by design (its own KDoc), so none of them
 * ever calls [IrohTransport.node] and none spawns a real [SidecarProcess].
 * This is the sidecar-gated complement, in the style of [IrohPeeringTest] and
 * [SidecarExchangeTest] — one process, one client, listening, then closed
 * (residual of task `computenet-ktn1l.1`, filed as `computenet-g0n34`).
 *
 * Skip-gated: without `-Piroh.enabled=true` this reports SKIPPED, never
 * failed — see [SidecarBinary].
 */
class IrohNodeSidecarTest {

    @Test
    fun `node spawns one sidecar, listens on it, and close shuts the endpoint down`() {
        val binary = SidecarBinary.orSkip()
        val registry = LocationRegistry()
        val side = Peering.Side(registry, ManagedHost(registry = registry), peer = PeerId("node"))

        val node = IrohTransport.node(side, binary)
        try {
            // The node's own id is the one sidecar process it spawned reporting
            // its identity over the one SidecarClient the node holds — not a
            // fixed or placeholder value.
            assertTrue(node.nodeId.isNotEmpty(), "a started node reports no nodeId")
            assertContentEquals(
                node.nodeId,
                node.client.getId(),
                "the node's nodeId is not what its one shared client's sidecar reports",
            )

            // start() already called LISTEN before node() returned; the
            // addresses it recorded are the LISTENING addresses.
            assertTrue(node.addresses.isNotEmpty(), "a started node recorded no LISTENING addresses")
        } finally {
            node.close()
        }

        // close() shuts the shared client down: any further control request on
        // it now refuses locally rather than reaching a sidecar that is gone.
        assertFailsWith<SidecarException>("the shared client answered after close()") {
            node.client.getId()
        }
    }
}
