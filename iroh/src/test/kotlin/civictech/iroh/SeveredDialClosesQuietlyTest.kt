package civictech.iroh

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.wire.Peering
import civictech.identity.Ed25519
import civictech.iroh.SidecarProtocol.DIRECTION_OUTBOUND
import civictech.iroh.SidecarProtocol.NODE_ID_LEN
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * computenet-g1aua at policy level (feature `computenet-gyvli`, gyvli-D2): a
 * re-dial that is **in flight** when [IrohTransport.IrohConnection.partition]
 * lands must not carry once it completes.
 *
 * The flake it closes (`IrohMirrorTransportTest`, run 34953772978): at instant
 * backoff an unplanned drop starts the re-dial loop, the test partitions while
 * the loop sits inside `openLink`, and the dial then returns and peers — "a ref
 * published while partitioned crossed anyway". A real sidecar answers a `DIAL`
 * whenever it likes, so that window cannot be held open over one; a
 * [FakeSidecar] that simply does not answer holds it open for as long as the
 * test needs, which makes the race a sequence rather than a timing.
 *
 * Runs on the default (sidecar-less) lane.
 */
class SeveredDialClosesQuietlyTest {

    private class Stack {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost)
    }

    private object NoSidecar : IrohTransport.Sidecar {
        override val nodeId: ByteArray get() = ByteArray(NODE_ID_LEN)
        override fun close() = Unit
    }

    @Test
    fun `a dial that completes after partition is closed quietly, never admitted and never charged`() {
        val peerNodeId = Ed25519.rawPublicKey(Ed25519.generateKeyPair().public)
        val b = Stack()
        // Every any-scope unpublish the registry fires — the AMENDS of gyvli.2's
        // review asks for the second one a lifted tombstone lets through to be
        // pinned harmless.
        val unpublished = CopyOnWriteArrayList<CellRef>()
        b.registry.onUnpublish { unpublished += it }

        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                val connection = IrohTransport.IrohConnection(
                    sidecar = NoSidecar,
                    client = client,
                    side = b.side,
                    peerNodeId = peerNodeId,
                    backoff = { 0L }, // instant, as in the flake
                    redialTimeout = 30.seconds,
                )
                try {
                    // ---- link 1: up and admitted ---------------------------
                    val link1 = fake.openAndAdmit(connection, peerNodeId)
                    await("the first link is peered") { connection.peered }
                    val mirror1 = connection.mirrorRef ?: fail("the first link minted no mirror")

                    // ---- an unplanned drop starts the re-dial loop ------------
                    fake.send(SidecarMessage.LinkDown(link1, "peer vanished"))
                    await("the first link went down") { !connection.peered }

                    // The loop's re-dial, deliberately NOT answered: the loop
                    // thread is now blocked inside openLink's `dial`.
                    val redial = fake.nextDial()

                    // ---- THE RACE: partition while that dial is in flight ----
                    connection.partition()
                    assertTrue(connection.severed, "partition() did not hold the connection severed")

                    // ---- the dial completes after the partition --------------
                    fake.send(SidecarMessage.LinkUp(redial.link, peerNodeId, DIRECTION_OUTBOUND))
                    // A gated dial writes no hello: the first thing on the
                    // link from this side is its close.
                    val first = fake.nextHostMessage()
                    assertEquals(
                        HostMessage.CloseLink(redial.link),
                        first,
                        "a dial that returned after partition() was not closed quietly — it sent " +
                            "${first::class.simpleName} instead (computenet-g1aua: the in-flight re-dial healed the sever)",
                    )
                    fake.send(SidecarMessage.LinkDown(redial.link, "closed by host"))

                    await("the gated dial is accounted") { connection.severedDialsClosed == 1L }
                    assertFalse(connection.peered, "a dial that returned after partition() was admitted")
                    assertFalse(connection.isCarrying, "a partitioned connection reports isCarrying=true")
                    assertEquals(0, connection.unadmittedOpens, "the quietly closed dial was charged as a refusal")
                    assertFalse(connection.abandonedAfterRefusals)
                    // ...and nothing re-dials while partitioned: held severed.
                    assertTrue(
                        neverWithin(500) { fake.dials.get() > 2L },
                        "the connection re-dialled while partitioned",
                    )
                    assertTrue(
                        neverWithin(200) { fake.pollHostMessage(50) is HostMessage.Data },
                        "the connection wrote a frame while partitioned",
                    )
                    // Re-read after the holds: the gated link's LINK_DOWN, sent
                    // above, has been dispatched by now, so a charge made when it
                    // was retired shows here (the read before the holds can win
                    // the race against that dispatch).
                    assertEquals(0, connection.unadmittedOpens, "the quietly closed dial's LINK_DOWN was charged")

                    // ---- heal: one fresh link, admitted ----------------------
                    val healing = Thread({ connection.heal() }, "g1aua-heal").apply { isDaemon = true; start() }
                    val healDial = fake.nextDial()
                    fake.send(SidecarMessage.LinkUp(healDial.link, peerNodeId, DIRECTION_OUTBOUND))
                    assertIs<HostMessage.Data>(fake.nextHostMessage(), "the healed link's hello")
                    fake.send(
                        SidecarMessage.Data(
                            healDial.link,
                            (IrohTransport.HELLO_PREFIX + UUID.randomUUID()).toByteArray(StandardCharsets.UTF_8),
                        ),
                    )
                    healing.join(30_000)
                    await("the healed link carries") { connection.isCarrying }
                    assertEquals(0, connection.unadmittedOpens)
                    assertEquals(3L, fake.dials.get(), "initial dial + the gated re-dial + the heal, and nothing else")

                    // ---- the first instance: retired, then superseded (D3) ---
                    await("the healed instance supersedes the first one") { mirror1 !in b.registry.retiredRefs() }
                    await("the first instance's mirror is despawned") {
                        b.bridgeHost.lookup(mirror1, Peering.AnnounceInletProxy::class.java) == null
                    }
                    assertTrue(b.registry.retiredRefs().isEmpty(), "tombstones left: ${b.registry.retiredRefs()}")
                    // The double unpublish is harmless: the despawn that may
                    // land after the lift re-fires onUnpublish for a ref with no
                    // location, and nothing reacts to it — the live instance
                    // still carries, and the retired ref is not re-published.
                    assertTrue(unpublished.count { it == mirror1 } in 1..2, "unpublish count for $mirror1: $unpublished")
                    assertEquals(null, b.registry.location(mirror1), "a retired mirror came back")
                    assertTrue(connection.isCarrying, "a late unpublish for a retired ref disturbed the live link")
                } finally {
                    connection.close()
                }
            }
        }
    }
}
