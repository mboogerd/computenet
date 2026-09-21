package civictech.iroh.discover

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import civictech.identity.Ed25519
import civictech.iroh.FakeSidecar
import civictech.iroh.HelloGate
import civictech.iroh.HostMessage
import civictech.iroh.IrohNode
import civictech.iroh.IrohTransport
import civictech.iroh.SidecarClient
import civictech.iroh.SidecarMessage
import civictech.iroh.await
import civictech.iroh.neverWithin
import org.junit.jupiter.api.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * [DiscoveredPeering.detach]: stop the policy and hand its live connections to
 * the caller instead of closing them (DSC2 feature `computenet-63um5`, task
 * `.1`, decision 63um5-D1).
 *
 * Same discipline as [DiscoveredPeeringTest], whose private `Rig` idiom this
 * file copies rather than imports: a [FakeSidecar] loopback, a [ManualTimer]
 * and a clock the test writes, no `Thread.sleep`. An absence is a bounded
 * window ([neverWithin], [drain]) and is stated as such.
 *
 * The last test is the premise 63um5-D1 rests on, run rather than read: a
 * planned `sever()` under a RUNNING policy is re-dialled, and under a detached
 * one it is not. That pair is what makes `detach()` necessary for a partition
 * to hold, and it is the discriminator for "the policy really stopped".
 */
class DiscoveredPeeringDetachTest {

    // --------------------------------------------------------------- fixtures

    private fun nodeId(): ByteArray = Ed25519.rawPublicKey(Ed25519.generateKeyPair().public)

    private fun side(): Peering.Side {
        val registry = LocationRegistry()
        return Peering.Side(registry, ManagedHost(registry = registry), peer = PeerId("node"))
    }

    private class FixedSidecar(override val nodeId: ByteArray) : IrohTransport.Sidecar {
        override fun close() = Unit
    }

    private class Rig(val fake: FakeSidecar, val client: SidecarClient, val node: IrohNode) : AutoCloseable {

        @Volatile
        var now: Long = 0L

        val timer = ManualTimer { now }

        lateinit var peering: DiscoveredPeering

        /** Every connection a test took over by `detach()`; closed at the end so no link outlives the rig. */
        val handed = mutableListOf<IrohTransport.IrohConnection>()

        fun advanceTo(instant: Long) {
            now = instant
            timer.advanceTo(instant)
        }

        fun discover(key: ByteArray) = fake.send(SidecarMessage.PeerDiscovered(key, listOf("127.0.0.1:1")))

        fun viewOf(key: ByteArray): PeerView? = peering.snapshot().firstOrNull { it.keyHex == NodeKey(key).hex }

        /** Discover [key], answer its `DIAL` and admit it, then wait for `Peered`. Returns the link id. */
        fun peer(key: ByteArray): Long {
            discover(key)
            val dial = fake.nextDial()
            assertTrue(dial.peerId.contentEquals(key))
            fake.admit(dial.link, key)
            await("$key to read as peered") { viewOf(key)?.state == "Peered(OUTBOUND)" }
            return dial.link
        }

        /**
         * Every host message written until the wire has been quiet for
         * [quietMillis] — the window an absence is asserted over. Announcement
         * `DATA` is expected on an admitted link and is returned with the rest.
         */
        fun drain(quietMillis: Long = 600): List<HostMessage> {
            val out = mutableListOf<HostMessage>()
            while (true) out += fake.pollHostMessage(quietMillis) ?: return out
        }

        override fun close() {
            handed.forEach { runCatching { it.close() } }
            runCatching { if (::peering.isInitialized) peering.close() }
            runCatching { client.close() }
            runCatching { fake.close() }
        }
    }

    private fun withPeering(policy: DialPolicy = DialPolicy(schedule = { SCHEDULE_MS }), body: (Rig) -> Unit) {
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                Rig(fake, client, IrohNode(FixedSidecar(OWN_ID), client, side())).use { rig ->
                    settle("node start") { rig.node.start(30.seconds) }.let { started ->
                        assertEquals(HostMessage.Listen, fake.nextHostMessage())
                        fake.send(SidecarMessage.Listening(listOf("127.0.0.1:1")))
                        started()
                    }
                    val startPeering = settle("peering start") {
                        DiscoveredPeering.start(rig.node, policy, clock = { rig.now }, timer = rig.timer)
                    }
                    assertEquals(HostMessage.WatchPeers, fake.nextHostMessage())
                    fake.send(SidecarMessage.Watching)
                    rig.peering = startPeering()
                    body(rig)
                }
            }
        }
    }

    private fun <T> settle(what: String, call: () -> T): () -> T {
        val done = ArrayBlockingQueue<Result<T>>(1)
        Thread({ done.put(runCatching { call() }) }, what).apply { isDaemon = true }.start()
        return { (done.poll(30, TimeUnit.SECONDS) ?: fail("$what did not settle within 30s")).getOrThrow() }
    }

    private fun closeLinks(messages: List<HostMessage>): List<HostMessage> = messages.filterIsInstance<HostMessage.CloseLink>()

    // ----------------------------------------------------------------- tests

    @Test
    fun `detach keeps the peered link up and hands the connection over`() {
        withPeering { rig ->
            val key = nodeId()
            val link = rig.peer(key)

            val handed = rig.peering.detach()
            rig.handed += handed.values

            assertEquals(setOf(NodeKey(key)), handed.keys, "exactly the one key this policy dialled")
            val connection = assertNotNull(handed[NodeKey(key)])
            assertTrue(connection.peered, "handed over with its link still up and admitted")
            assertNull(rig.peering.connectionFor(NodeKey(key)), "the policy's own map is emptied: ownership moved to the caller")
            assertEquals(
                emptyList(),
                closeLinks(rig.drain()),
                "detach() closes no link (bounded window: until the wire is quiet for 600ms)",
            )

            rig.peering.close()
            assertEquals(emptyList(), closeLinks(rig.drain()), "close() after detach() closes nothing further")
            assertTrue(connection.peered, "and the link is still the caller's, still up")

            connection.close()
            assertEquals(
                listOf<HostMessage>(HostMessage.CloseLink(link)),
                closeLinks(rig.drain()),
                "closing the returned connection is what closes the link",
            )
        }
    }

    @Test
    fun `after detach a discovery event is dropped`() {
        withPeering { rig ->
            // A live policy first, so the frozen counters are non-trivial.
            val first = nodeId()
            rig.discover(first)
            rig.fake.nextDial()
            await("the first event to be counted") { rig.peering.counters.dialsAttempted.count == 1L }

            rig.handed += rig.peering.detach().values
            val events = rig.peering.counters.eventsReceived.count
            val dials = rig.peering.counters.dialsAttempted.count
            val onWire = rig.fake.dials.get()

            rig.discover(nodeId())
            rig.advanceTo(SCHEDULE_MS + 1)

            assertTrue(neverWithin(1_000) { rig.fake.dials.get() > onWire }, "a detached policy dials nothing (1s window)")
            assertTrue(rig.drain().none { it is HostMessage.Dial }, "no DIAL reached the wire")
            assertEquals(events, rig.peering.counters.eventsReceived.count, "eventsReceived is frozen")
            assertEquals(dials, rig.peering.counters.dialsAttempted.count, "dialsAttempted is frozen")
            assertEquals(1, rig.peering.snapshot().size, "snapshot() stays readable and does not learn the new key")
        }
    }

    @Test
    fun `detach restores ADMIT_ALL and is idempotent`() {
        withPeering { rig ->
            val key = nodeId()
            rig.peer(key)
            assertNotSame(HelloGate.ADMIT_ALL, rig.node.gate, "a running policy has its own gate installed")

            val handed = rig.peering.detach()
            rig.handed += handed.values
            assertEquals(1, handed.size)
            assertSame(HelloGate.ADMIT_ALL, rig.node.gate, "detach() puts the node's gate back")

            assertEquals(emptyMap(), rig.peering.detach(), "a second detach() hands over nothing")
            assertSame(HelloGate.ADMIT_ALL, rig.node.gate)
            rig.peering.close()
            assertTrue(assertNotNull(handed[NodeKey(key)]).peered, "neither the second detach nor close touched it")
        }
    }

    @Test
    fun `detach after close returns an empty map`() {
        withPeering { rig ->
            rig.peer(nodeId())
            rig.peering.close()
            assertEquals(emptyMap(), rig.peering.detach(), "close() already owned and closed the connections")
        }
    }

    /**
     * 63um5-D1's premise, executed: `sever()` is a planned close and reports
     * its down with no outcome, and a RUNNING policy answers that down with a
     * re-dial (`PeerTable.linkDown` -> `Redial`, then `pump()`). After
     * `detach()` the same sever stays severed. The first half is the reason
     * the method exists; the second is the proof it stopped the policy.
     */
    @Test
    fun `a planned sever is re-dialled by a running policy and not by a detached one`() {
        withPeering { rig ->
            val running = nodeId()
            val link = rig.peer(running)
            val connection = assertNotNull(rig.peering.connectionFor(NodeKey(running)))
            connection.sever()
            await("the sever's CLOSE_LINK") { rig.fake.pollHostMessage(200) == HostMessage.CloseLink(link) }
            rig.fake.send(SidecarMessage.LinkDown(link, "closed"))
            val redial = rig.fake.nextDial()
            assertTrue(redial.peerId.contentEquals(running), "the running policy re-dialled the severed key")

            val detached = nodeId()
            val link2 = rig.peer(detached)
            val handed = rig.peering.detach()
            rig.handed += handed.values
            val onWire = rig.fake.dials.get()

            assertNotNull(handed[NodeKey(detached)]).sever()
            await("the second sever's CLOSE_LINK") { rig.fake.pollHostMessage(200) == HostMessage.CloseLink(link2) }
            rig.fake.send(SidecarMessage.LinkDown(link2, "closed"))
            rig.advanceTo(SCHEDULE_MS * 10)

            assertTrue(neverWithin(1_000) { rig.fake.dials.get() > onWire }, "a detached policy does not re-dial a sever (1s window)")
            assertTrue(rig.drain().none { it is HostMessage.Dial }, "no DIAL reached the wire")
        }
    }

    private companion object {
        val OWN_ID: ByteArray = Ed25519.rawPublicKey(Ed25519.generateKeyPair().public)
        const val SCHEDULE_MS = 1_000L
    }
}
