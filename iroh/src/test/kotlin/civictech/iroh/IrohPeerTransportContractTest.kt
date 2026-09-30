package civictech.iroh

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.wire.PeerAddress
import civictech.cell.wire.PeerTransport
import civictech.cell.wire.PeerTransports
import civictech.cell.wire.Peering
import civictech.cell.wire.ReconnectPolicy
import civictech.testkit.PeerTransportContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom

/**
 * [PeerTransportContract] against [IrohPeerTransport] over a **real** sidecar
 * (feature `computenet-gyvli`, gyvli-D8 rule 1). Every case takes
 * [SidecarBinary]'s assumption, so without `-Piroh.enabled=true` the whole
 * class reports SKIPPED; CI's `iroh-sidecar` check runs it with the flag
 * (`:iroh:check`, unfiltered).
 *
 * Instant backoff, so the hold cases (c) and (e) test the policy's intent
 * flags: a re-dial armed after a partition or a close would carry well inside
 * the hold.
 *
 * Beyond the shared cases it runs the refused-dial hook the contract leaves to
 * the socket bindings, and case (c) [G1AUA_ROUNDS] times over one peering —
 * the computenet-g1aua evidence at the seam. The race itself (a re-dial in
 * flight when the partition lands) cannot be held open over a real sidecar;
 * `SeveredDialClosesQuietlyTest` holds it open with a fake one.
 */
class IrohPeerTransportContractTest : PeerTransportContract() {

    override fun transport(): PeerTransport = IrohPeerTransport(SidecarBinary.orSkip(), backoff = { 0L })

    override fun listenAddress(): PeerAddress = transport.parseAddress("iroh://")

    override val maxHelloStatements: Int = IrohTransport.MAX_HELLO_STATEMENTS

    @Test
    override fun `a refused dial abandons after the refused-dial limit`() {
        // A listener whose allowlist admits nobody the dialler can resolve to:
        // every hello is refused, so every link comes up and goes down unadmitted.
        val registry = LocationRegistry()
        val refusing = Peering.Side(registry, ManagedHost(registry = registry), allow = setOf(PeerId("nobody")))
        val listener = transport.listen(listenAddress(), refusing).closedAfter()
        val refused = assertThrows(IrohPeerTransport.DialRefusedException::class.java) {
            transport.dial(listener.boundAddress, stack("dialler").side).closedAfter()
        }
        val limit = ReconnectPolicy.REFUSED_DIAL_LIMIT
        assertTrue(
            refused.unadmittedOpens in limit..(limit + 1),
            "gave up after ${refused.unadmittedOpens} unadmitted opens; the limit is $limit (plus the one in flight)",
        )
    }

    @Test
    fun `(c) x20 - a ref spawned while partitioned never crosses, round after round at instant backoff`() {
        val p = peered()
        p.awaitCarried()
        repeat(G1AUA_ROUNDS) { round ->
            p.connection.partition()
            assertFalse(p.connection.isCarrying, "round $round: a partitioned connection reports isCarrying=true")
            val during = p.listening.spawnCell()
            neverWithin("round $round: ${during.id}, spawned while partitioned, became Remote on the dialler", HOLD_MS) {
                p.dialling.seesRemote(during)
            }
            p.connection.heal()
            p.awaitCarried(during)
        }
    }

    @Test
    fun `boundAddress carries the sidecar's real node id and listening addresses`() {
        val listener = transport.listen(listenAddress(), stack("listener").side).closedAfter()
        val bound = transport.parseAddress(listener.boundAddress.text) as IrohPeerTransport.IrohAddress
        assertEquals(64, bound.nodeIdHex.length, "no node id in ${bound.text}")
        assertTrue(bound.addresses.isNotEmpty(), "no listening addresses in ${bound.text}")
        // and it is dialable as reported: nothing synthetic about it
        val connection = transport.dial(bound, stack("dialler").side).closedAfter()
        assertTrue(connection.isCarrying)
    }

    @Test
    fun `a secretKey pins the listener's node id across two listens`() {
        val key = ByteArray(32).also(SecureRandom()::nextBytes).toHex()
        val pinned = IrohPeerTransport(SidecarBinary.orSkip(), secretKey = key, backoff = { 0L })
        val first = pinned.listen(listenAddress(), stack("first").side).use { it.boundAddress }
        val second = pinned.listen(listenAddress(), stack("second").side).use { it.boundAddress }
        assertEquals(
            (first as IrohPeerTransport.IrohAddress).nodeIdHex,
            (second as IrohPeerTransport.IrohAddress).nodeIdHex,
        )
        // and without one, each listen is a fresh key
        val a = transport.listen(listenAddress(), stack("a").side).use { it.boundAddress as IrohPeerTransport.IrohAddress }
        val b = transport.listen(listenAddress(), stack("b").side).use { it.boundAddress as IrohPeerTransport.IrohAddress }
        assertNotEquals(a.nodeIdHex, b.nodeIdHex)
    }

    @Test
    fun `the provider is found by scheme and builds a binding from its config`() {
        val created = PeerTransports.forScheme(
            IrohPeerTransport.SCHEME,
            mapOf("binary" to SidecarBinary.orSkip().toString()),
        )
        assertEquals(IrohPeerTransport.SCHEME, created.scheme)
    }

    companion object {
        /** Rounds of case (c) the g1aua evidence runs; the task asks for at least 20. */
        const val G1AUA_ROUNDS: Int = 20
    }
}
