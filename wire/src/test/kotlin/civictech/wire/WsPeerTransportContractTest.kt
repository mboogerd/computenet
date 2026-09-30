package civictech.wire

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.wire.PeerAddress
import civictech.cell.wire.PeerTransport
import civictech.cell.wire.PeerTransports
import civictech.cell.wire.Peering
import civictech.cell.wire.ReconnectPolicy
import civictech.testkit.PeerTransportContract
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [PeerTransportContract] against [WsPeerTransport] over real loopback sockets
 * (feature `computenet-gyvli`, gyvli-D8 rule 1), plus the `ws`-specific
 * acceptance of task `computenet-gyvli.3`: case (h), the computenet-vzb
 * probe, and computenet-8uv6's decided close.
 *
 * A 20 ms backoff stands in for "instant": the hold cases (c) and (e) then
 * test the policy's intent flags — were a re-dial armed after a partition or
 * a close, it would carry well inside the 2 s hold.
 */
class WsPeerTransportContractTest : PeerTransportContract() {

    override fun transport(): PeerTransport = WsPeerTransport(backoff = { 20L })

    override fun listenAddress(): PeerAddress = transport.parseAddress("ws://localhost:0")

    override val maxHelloStatements: Int = MAX_HELLO_STATEMENTS

    /**
     * (h) A dialler the listener's allowlist refuses abandons after exactly
     * [ReconnectPolicy.REFUSED_DIAL_LIMIT] unadmitted opens, and its policy
     * no longer wants a re-dial. `@Test` is repeated on purpose — JUnit 5 does
     * not inherit it onto an override.
     */
    @Test
    override fun `a refused dial abandons after the refused-dial limit`() {
        val registry = LocationRegistry()
        val refusing = Peering.Side(registry, ManagedHost(registry = registry), allow = emptySet())
        val listener = transport.listen(listenAddress(), refusing).closedAfter()
        val refused = assertThrows(WsPeerTransport.DialRefusedException::class.java) {
            transport.dial(listener.boundAddress, stack("refused").side).closedAfter()
        }
        assertEquals(ReconnectPolicy.REFUSED_DIAL_LIMIT, refused.stats.unadmittedOpens, refused.message)
        assertFalse(refused.shouldRedial, "an abandoned dial's policy still wanted a re-dial")
    }

    /**
     * computenet-8uv6, decided per gyvli-D4: closing a seam connection is
     * deliberate. No `ws-reconnect-` thread is started for it and the
     * listener sees no further session from it, even at a 20 ms backoff.
     */
    @Test
    fun `a seam close starts no reconnect thread and the listener accepts nothing more from it`() {
        val p = peered()
        p.awaitCarried()
        val raw = (p.listener as WsPeerTransport.Listener).raw
        val threadName = "ws-reconnect-${p.listener.boundAddress.text}"

        p.connection.close()

        awaitUntil("the listener sees the closed session go", CARRY_MS) { raw.liveSessions == 0 }
        neverWithin("a $threadName thread ran, or the listener accepted another session", HOLD_MS) {
            raw.liveSessions > 0 || Thread.getAllStackTraces().keys.any { it.name == threadName }
        }
    }

    /**
     * The other half of gyvli-D4: a close the PEER initiates re-arms, and the
     * seam connection reconnects on its policy's schedule — the raw API's
     * behaviour `WsReconnectSmokeTest` pins, reached through the seam.
     */
    @Test
    fun `a peer close reconnects on the policy's schedule`() {
        val p = peered()
        p.awaitCarried()
        val raw = (p.listener as WsPeerTransport.Listener).raw

        raw.connections.forEach { it.close() } // the listener hangs up; the dialler did not ask for it

        val after = p.listening.spawnCell()
        p.awaitCarried(after)
        awaitUntil("the dialler carries again", CARRY_MS) { p.connection.isCarrying }
        assertEquals(0, p.connection.stats.unadmittedOpens, "an admitted reconnect left a refused-dial run")
    }

    /**
     * computenet-vzb's probe, on the shape it was measured on: ONE
     * [WsTransport.Session] driven through ten hello/close cycles, each
     * admitted. Before gyvli-D3 each cycle left a `BridgeIngressCell` and a
     * `RegistryMirrorCell` spawned and published, so `localRefs()` grew by two
     * per cycle; now the tenth admission leaves the side where the first did,
     * no retired instance's cell is still hosted on the bridge, and every
     * retired instance but the most recent has had its tombstones lifted.
     */
    @Test
    fun `ten hello-close cycles on one Session leave the side's refs flat`() {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = PeerId("jvm-a"))
        val app = SetCell<String>().also { host.managementInlet.call.spawn(it) }.ref
        awaitUntil("the application cell is published", CARRY_MS) { app in registry.localRefs() }
        val appRefs = registry.localRefs()

        val session = WsTransport.Session(side, send = {}, refuse = {})
        val instances = mutableListOf<Set<CellRef>>()
        fun cycle() {
            session.hello()
            session.onText("HELLO ${UUID.randomUUID()} jvm-b")
            // the bridge host runs its management calls in order: once this
            // instance's two spawns are published, the previous close's
            // despawns have run too
            awaitUntil("cycle ${instances.size}'s instance is spawned", CARRY_MS) {
                registry.localRefs().size == appRefs.size + 2
            }
            instances += registry.localRefs() - appRefs
        }

        cycle()
        val afterFirst = registry.localRefs().size
        repeat(CYCLES - 1) {
            session.onClose()
            cycle()
        }

        assertEquals(afterFirst, registry.localRefs().size, "localRefs after $CYCLES hello/close cycles vs after the first")
        assertTrue(instances.zipWithNext().all { (a, b) -> a.intersect(b).isEmpty() }, "a cycle reused an instance's refs")
        val retired = instances.dropLast(1)
        awaitUntil("no retired instance's cell is still hosted on the bridge", CARRY_MS) {
            retired.flatten().none { bridgeHost.portAt(it, "inlet") != null }
        }
        val lifted = retired.dropLast(1).flatten()
        assertTrue(
            registry.retiredRefs().none { it in lifted },
            "tombstones of superseded, despawned instances were never lifted: ${registry.retiredRefs().intersect(lifted.toSet())}",
        )
    }

    @Test
    fun `the ws binding is discoverable by its scheme`() {
        assertTrue(PeerTransports.forScheme(WsPeerTransport.SCHEME) is WsPeerTransport)
    }
}
