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
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
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

        // now the bridge host runs: the staged frame decodes and is delivered,
        // both hops, entirely after the reconnect completed
        controller.runToIdle()

        // `dropped` is a ref the peer let go of while it was away, so the
        // re-hello's catch-up never re-announced it. Only the stale frame could
        // have installed it — and it must not have. Asserted before the
        // identity check below so a regression reports the fence, not a symptom.
        registry.location(dropped).shouldBeNull()
        registry.remoteRefs().contains(dropped) shouldBe false

        // the re-hello opened a new connection instance, addressable in its own
        // right — this is what the client path did not do before dqy.14, and it
        // is the mechanism the assertions above depend on
        secondInstance shouldNotBe firstInstance
    }

    /**
     * The harder half of "both hops". The case above stages a frame that is
     * still *undecoded* when the socket dies, which an ingress epoch would also
     * have caught. This one lets the ingress decode run first, so the close
     * finds an invocation already handed to `LocationRegistry.deliver` and
     * queued for the mirror — past every point an epoch on the decode hop could
     * reach. Addressing by mirror ref still fences it, because the queued
     * invocation names the retired instance's cell.
     *
     * How many scheduler steps the two hops take is measured, not assumed: a
     * control run counts steps to the install, and the fence run stops one step
     * short of it. So the assertion below is about a delivery that was provably
     * one step from being applied when the connection was superseded.
     */
    @Test
    fun `a frame the superseded connection had already decoded is fenced at the delivery hop`() {
        // ---- control: how many steps do the two hops take, undisturbed? ----
        val stepsToInstall = run {
            val controller = SimulationController(14)
            val registry = LocationRegistry()
            val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
            val side = Peering.Side(registry, bridgeHost, peer = PeerId("jvm-a"))
            val session = WsTransport.Session(side, send = {}, refuse = {})
            val instance = mirrorOffered(session.hello())
            session.onText("HELLO ${UUID.randomUUID()} jvm-b")
            controller.runToIdle() // settle the spawns, so only the frame's own hops are counted

            val announced = CellRef(UUID.randomUUID())
            session.onFrame(Peer().announces(announced, toMirror = instance))
            var steps = 0
            while (registry.location(announced) == null) {
                check(controller.step()) { "bridge host went idle without applying the announcement" }
                steps++
            }
            steps
        }
        // the decode and the delivery are separate scheduler tasks — the premise
        // of the whole fence argument, asserted rather than assumed
        stepsToInstall shouldBeGreaterThanOrEqual 2

        // ---- the same run, stopped one step short of the install ----------
        val controller = SimulationController(14)
        val registry = LocationRegistry()
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = PeerId("jvm-a"))
        val session = WsTransport.Session(side, send = {}, refuse = {})

        val firstInstance = mirrorOffered(session.hello())
        session.onText("HELLO ${UUID.randomUUID()} jvm-b")
        controller.runToIdle()

        val dropped = CellRef(UUID.randomUUID())
        session.onFrame(Peer().announces(dropped, toMirror = firstInstance))
        repeat(stepsToInstall - 1) {
            check(controller.step()) { "bridge host went idle early" }
        }
        // decoded, delivered to the registry, queued for the mirror — and one
        // step from landing
        registry.location(dropped).shouldBeNull()

        // ---- and now the socket dies and the SAME Session reconnects ------
        session.onClose()
        val secondInstance = mirrorOffered(session.hello())
        session.onText("HELLO ${UUID.randomUUID()} jvm-b")
        controller.runToIdle()

        registry.location(dropped).shouldBeNull()
        registry.remoteRefs().contains(dropped) shouldBe false
        secondInstance shouldNotBe firstInstance
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
