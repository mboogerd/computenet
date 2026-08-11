package civictech.wire

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.wire.BridgeEgressCell
import civictech.cell.wire.Peering
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.UUID

/**
 * computenet-dqy.14 — the disconnect fence on the **client** path.
 *
 * `MirrorCloseFenceTest` (computenet-dqy.5) already covers the fence itself,
 * including a reconnect. It does not cover this: it drives a bare
 * `RegistryMirrorCell` and so re-attaches a mirror the test itself owns, which
 * is the *loopback* shape. The shape that was actually broken is
 * [WsTransport.WsConnection]'s — **one [WsTransport.Session] reused across
 * every reconnect**, so one egress and (before this fix) one mirror spanning
 * two different connections. A listener never has it: `WsListener.onOpen`
 * builds a fresh Session per socket.
 *
 * So this test holds a single Session across two hello cycles and never
 * constructs a second one. That is the whole point of the setup; a version of
 * it that made a new Session for the reconnect would be re-testing the listener
 * path and would have passed before the fix.
 *
 * **Deterministic, not a race.** No socket, no reconnect timer: the Session's
 * IO-thread callbacks (`hello`/`onText`/`onFrame`/`onClose`) are called
 * directly, and the bridge host runs on a [SimulationController], so "the frame
 * the dead connection left staged" is an explicit un-drained queue rather than
 * a thread the test hopes to lose. Both scheduler hops behind the socket — the
 * ingress decode and the delivery to the mirror — are held by that controller.
 */
class WsClientReconnectFenceTest {

    /**
     * The far side of the wire: turns "peer announces `ref` to the mirror at
     * `mirrorRef`" into the exact bytes that peer would have put on the socket.
     * This is [Peering.announceTo]'s encoder — a [BridgeEgressCell] fronting an
     * announce proxy — with the socket replaced by a list.
     */
    private class Peer {
        private val frames = mutableListOf<ByteArray>()
        private val egress = BridgeEgressCell().also { cell ->
            cell.outlet.subscribe(
                Use.fixed(
                    object : Propagate<ByteArray> {
                        override fun propagate(value: ByteArray) {
                            frames += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
        }

        fun announces(published: CellRef, toMirror: CellRef): ByteBuffer {
            val announce = (HostedCellProxy.create(toMirror, egress, Peering.AnnounceInletProxy::class.java)
                    as Peering.AnnounceInletProxy).inlet.call
            frames.clear()
            announce.published(published)
            check(frames.size == 1) { "expected exactly one announcement frame, got ${frames.size}" }
            return ByteBuffer.wrap(frames.single())
        }
    }

    /** The mirror ref a hello offers — the connection instance's identity on the wire. */
    private fun mirrorOffered(hello: String): CellRef =
        CellRef(UUID.fromString(hello.removePrefix("HELLO ").trim().substringBefore(" ")))

    @Test
    fun `a frame staged by the superseded connection cannot install a Remote after the client re-hellos`() {
        val controller = SimulationController(14)
        val registry = LocationRegistry()
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = PeerId("jvm-a"))

        // ONE Session for the whole test: the WsConnection client shape.
        val session = WsTransport.Session(side, send = {}, refuse = {})
        val peer = Peer()

        // ---- connection instance 1 -------------------------------------
        val firstInstance = mirrorOffered(session.hello())
        session.onText("HELLO ${UUID.randomUUID()} jvm-b")

        // The peer announces a ref, and the frame reaches the bridge host —
        // where it STAYS: the controller is deliberately not drained, which is
        // exactly the state the queue is in when a socket dies mid-burst.
        val dropped = CellRef(UUID.randomUUID())
        session.onFrame(peer.announces(dropped, toMirror = firstInstance))

        // ---- the socket dies, and the SAME Session reconnects -----------
        session.onClose()
        val secondInstance = mirrorOffered(session.hello())
        session.onText("HELLO ${UUID.randomUUID()} jvm-b")

        // the re-hello opened a new connection instance, addressable in its own
        // right — this is what the client path did not do before dqy.14
        secondInstance shouldNotBe firstInstance

        // now the bridge host runs: the staged frame decodes and is delivered,
        // both hops, entirely after the reconnect completed
        controller.runToIdle()

        // `dropped` is a ref the peer let go of while it was away, so the
        // re-hello's catch-up never re-announced it. Only the stale frame could
        // have installed it — and it must not have.
        registry.location(dropped).shouldBeNull()
        registry.remoteRefs().contains(dropped) shouldBe false
    }

    @Test
    fun `the live connection instance still mirrors after a reconnect on the same Session`() {
        val controller = SimulationController(14)
        val registry = LocationRegistry()
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = PeerId("jvm-a"))

        val session = WsTransport.Session(side, send = {}, refuse = {})
        val peer = Peer()

        session.hello()
        session.onText("HELLO ${UUID.randomUUID()} jvm-b")
        session.onClose()

        // the fence is a fence, not a mute: the instance that comes back
        // announces into a mirror of its own and is mirrored normally, under
        // the name its re-hello asserts (V4-PEERID)
        val live = mirrorOffered(session.hello())
        session.onText("HELLO ${UUID.randomUUID()} jvm-b")
        val theirs = CellRef(UUID.randomUUID())
        session.onFrame(peer.announces(theirs, toMirror = live))
        controller.runToIdle()

        (registry.location(theirs) as LocationRegistry.Remote).peer shouldBe PeerId("jvm-b")
    }
}
