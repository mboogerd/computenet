package civictech.demo.beadsmirror

import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [IrohMirrorTransport] through the [MirrorTransport] interface alone (task
 * `computenet-egl.4.1`): listen, dial, partition, heal — asserted on what the
 * *seam* promises, never on `:iroh` internals, because the point of the
 * binding is that the suite above it cannot tell which transport it got.
 *
 * The observables are therefore registry announcements: a ref published on one
 * side becoming `LocationRegistry.Remote` on the other is "the peering
 * carries", and a ref published *while severed* staying absent is "the
 * partition held". Both are ordinary consequences of the bridge cells and need
 * no iroh vocabulary.
 *
 * Deliberately NOT under `e2e/` — this is the binding's own unit-scale test,
 * with no `bd`, no Dolt and no rig; the `ConvergenceSuite` instantiation is a
 * separate item.
 *
 * Skip-gated: without `-Piroh.enabled=true` (hence without a built sidecar)
 * every test here reports SKIPPED, never failed — see [IrohSidecarGate].
 */
class IrohMirrorTransportTest {

    private class Stack(name: String) {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = PeerId(name))
    }

    /** Near-zero, so an unplanned re-dial costs scheduling and not wall clock (the T12 seam). */
    private val instantBackoff: (attempt: Int) -> Long = { 1L }

    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out awaiting: $what")
            Thread.sleep(50)
        }
    }

    /** Nothing ever became true within [millis] — used to pin an absence. */
    private fun neverWithin(millis: Long = 3_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return false
            Thread.sleep(50)
        }
        return !condition()
    }

    @Test
    fun `listen and dial through the seam carry a peering, and partition holds until heal`() {
        val binary = IrohSidecarGate.orSkip()
        val transport = IrohMirrorTransport(binary, reconnectBackoff = instantBackoff)
        val listening = Stack("listening")
        val dialling = Stack("dialling")

        transport.listen(requestedWsPort = 0, side = listening.side).use { listenLink ->
            // ---- the address model MirrorTransport documents ---------------
            val synthetic = assertNotNull(
                listenLink.boundWsPort,
                "TwoNodeRig checkNotNulls this; a binding whose addresses are not ports must still supply one",
            )
            assertTrue(synthetic > 0, "boundWsPort was $synthetic")

            // dial()'s String is an opaque token: this one names nothing that
            // exists, and the peering comes up anyway.
            transport.dial("ws://opaque.invalid:$synthetic", dialling.side).use { dialLink ->
                assertNull(dialLink.boundWsPort, "a dialling end binds no port, as WsMirrorTransport's does not")

                // ---- the peering carries -------------------------------------
                val beforeOnListening = SetCell<String>()
                listening.host.managementInlet.call.spawn(beforeOnListening)
                val beforeOnDialling = SetCell<String>()
                dialling.host.managementInlet.call.spawn(beforeOnDialling)

                await("the dialling side learns a ref published on the listening side") {
                    dialling.registry.location(beforeOnListening.ref) is LocationRegistry.Remote
                }
                await("the listening side learns a ref published on the dialling side") {
                    listening.registry.location(beforeOnDialling.ref) is LocationRegistry.Remote
                }

                // ---- partition, and it STAYS severed --------------------------
                transport.partition()

                val duringPartition = SetCell<String>()
                listening.host.managementInlet.call.spawn(duringPartition)
                assertTrue(
                    neverWithin { dialling.registry.location(duringPartition.ref) is LocationRegistry.Remote },
                    "a ref published while partitioned crossed anyway: the re-dial loop healed the sever",
                )

                // ---- heal, and it carries again ------------------------------
                transport.heal()

                // heal() returns with the link already carrying (the binding
                // waits for `peered`), so the ONLY thing still outstanding is
                // the announcement catch-up — including the ref minted while
                // severed, which rides out on the fresh hello's full localRefs
                // sweep.
                await("the ref minted during the partition arrives after heal") {
                    dialling.registry.location(duringPartition.ref) is LocationRegistry.Remote
                }

                val afterHeal = SetCell<String>()
                dialling.host.managementInlet.call.spawn(afterHeal)
                await("a ref published after heal crosses the re-established peering") {
                    listening.registry.location(afterHeal.ref) is LocationRegistry.Remote
                }
            }
        }
    }

    @Test
    fun `partition before dial fails loudly rather than silently doing nothing`() {
        val transport = IrohMirrorTransport(java.nio.file.Path.of("/nonexistent/sidecar"))
        // No sidecar is spawned on this path, so it needs no gate: the check
        // fires before anything touches the binary.
        val failure = runCatching { transport.partition() }.exceptionOrNull()
        assertTrue(
            failure is IllegalStateException,
            "partition on an undialled transport must fail loudly; got $failure",
        )
    }

    @Test
    fun `dial before listen fails loudly rather than defaulting to some peer`() {
        val transport = IrohMirrorTransport(java.nio.file.Path.of("/nonexistent/sidecar"))
        val stack = Stack("orphan")
        val failure = runCatching { transport.dial("ws://127.0.0.1:1", stack.side) }.exceptionOrNull()
        assertTrue(
            failure is IllegalStateException,
            "dial without a held listener must fail loudly; got $failure",
        )
    }

    @Test
    fun `the synthetic port is parsed out of the first LISTENING address`() {
        assertEquals(49812, IrohMirrorTransport.syntheticPort(listOf("127.0.0.1:49812", "192.168.1.4:49812")))
        // IPv6 literal: split on the LAST colon, not the first.
        assertEquals(49812, IrohMirrorTransport.syntheticPort(listOf("[::1]:49812")))
        // Nothing parses -> the documented fallback, because the contract is
        // only "non-null".
        assertEquals(
            IrohMirrorTransport.SYNTHETIC_PORT,
            IrohMirrorTransport.syntheticPort(listOf("not-an-address")),
        )
        assertEquals(IrohMirrorTransport.SYNTHETIC_PORT, IrohMirrorTransport.syntheticPort(emptyList()))
    }
}
