package civictech.iroh

import civictech.cell.DenialReason
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The hello state machine of [IrohTransport.Session], driven directly — no
 * sidecar, no iroh, so this runs on the DEFAULT lanes (it never calls
 * [SidecarBinary.orSkip]).
 *
 * That is deliberate rather than a shortcut: the drop path this pins is
 * reachable only from a peer that sends more frames after a hello this side
 * refused, which over two real sidecars is a race against the `CLOSE_LINK` the
 * refusal issues. Driving the Session directly makes it a fact instead of a
 * timing accident. `IrohPeeringTest` covers the same state machine over real
 * links.
 */
class IrohSessionHelloTest {

    private fun side(name: String? = null, allow: Set<KeyId>? = null): Peering.Side {
        val registry = LocationRegistry()
        return Peering.Side(registry, ManagedHost(registry = registry), peer = name?.let { PeerId(it) }, allow = allow)
    }

    private fun hello(mirrorRef: UUID = UUID.randomUUID(), name: String? = null): ByteArray =
        (IrohTransport.HELLO_PREFIX + mirrorRef + (name?.let { " $it" } ?: "")).toByteArray(StandardCharsets.UTF_8)

    @Test
    fun `frames after a refused hello are dropped and counted, never routed`() {
        val sent = mutableListOf<ByteArray>()
        var refusals = 0
        val session = IrohTransport.Session(side(), send = { sent += it }, refuse = { refusals++ })

        assertEquals(0L, session.preHelloDrops)

        // The first frame on a link IS the hello (positional grammar); this one
        // is not one, so it is refused and the link is closed.
        session.onData("nonsense".toByteArray(StandardCharsets.UTF_8))
        assertEquals(1, refusals)
        assertEquals(1L, session.admissionDenialCount)
        assertEquals(DenialReason.MALFORMED_HELLO, assertNotNull(session.lastAdmissionDenial).reason)
        assertFalse(session.peered, "a refused hello must install no ingress")
        assertEquals(0L, session.preHelloDrops, "the refused hello itself is not a drop")

        // Anything the peer wrote before the close lands here: nowhere to route,
        // so dropped — and now counted.
        session.onData(byteArrayOf(1, 2, 3))
        assertEquals(1L, session.preHelloDrops)
        session.onData(byteArrayOf(4, 5, 6))
        assertEquals(2L, session.preHelloDrops)

        assertTrue(sent.isEmpty(), "a refused link must never be written to")
    }

    @Test
    fun `an admitted hello mints a mirror, answers with our own hello, and installs the ingress`() {
        val sent = mutableListOf<ByteArray>()
        val session = IrohTransport.Session(side(name = "local"), send = { sent += it }, refuse = { })

        session.onData(hello(name = "remote"))

        assertTrue(session.peered, "an admitted hello installs the ingress")
        assertEquals(0L, session.preHelloDrops)
        assertEquals(0L, session.admissionDenialCount)
        // The hello is our FIRST frame and our only one: everything after it is
        // the announcement catch-up `bindAndAnnounce` starts, which is ordinary
        // wire traffic (the bridge host's own mirror and ingress cells are local
        // refs, so `announceTo`'s sweep names them).
        val answer = String(sent.first(), StandardCharsets.UTF_8)
        assertTrue(answer.startsWith(IrohTransport.HELLO_PREFIX), "our first frame is a hello: $answer")
        assertEquals(
            1,
            sent.count { String(it, StandardCharsets.UTF_8).startsWith(IrohTransport.HELLO_PREFIX) },
            "exactly one hello is ever written to a link",
        )
        assertTrue(answer.endsWith(" local"), "our hello asserts this side's peer name: $answer")
        assertEquals(
            assertNotNull(session.mirrorRef).id.toString(),
            answer.removePrefix(IrohTransport.HELLO_PREFIX).substringBefore(' '),
            "our hello names this link instance's mirror",
        )
    }

    @Test
    fun `a hello from a peer off the allowlist is refused, accounted, and binds nothing`() {
        val sent = mutableListOf<ByteArray>()
        var refusals = 0
        val session = IrohTransport.Session(
            side(name = "server", allow = setOf(KeyId("good"))),
            send = { sent += it },
            refuse = { refusals++ },
        )

        session.onData(hello(name = "mallory"))

        assertEquals(1, refusals, "the link is closed")
        assertEquals(1L, session.admissionDenialCount)
        val denial = assertNotNull(session.lastAdmissionDenial)
        assertEquals(DenialReason.NOT_ADMITTED, denial.reason)
        assertEquals(PeerId("mallory"), denial.principal)
        assertFalse(session.peered, "no ingress on a refused hello")
        assertEquals(null, session.mirrorRef, "a refused peer costs this side no mirror")
        assertTrue(sent.isEmpty(), "nothing is written to a refused link — not even our hello")

        // and an admitted name on the same allowlist still passes
        val admitted = IrohTransport.Session(
            side(name = "server", allow = setOf(KeyId("good"))),
            send = { },
            refuse = { throw AssertionError("an admitted hello must not be refused") },
        )
        admitted.onData(hello(name = "good"))
        assertTrue(admitted.peered)
        assertEquals(0L, admitted.admissionDenialCount)
    }

    @Test
    fun `a hello whose first token is not a mirror ref is refused as malformed`() {
        var refusals = 0
        val session = IrohTransport.Session(side(), send = { }, refuse = { refusals++ })

        session.onData((IrohTransport.HELLO_PREFIX + "not-a-uuid peer").toByteArray(StandardCharsets.UTF_8))

        assertEquals(1, refusals)
        assertEquals(DenialReason.MALFORMED_HELLO, assertNotNull(session.lastAdmissionDenial).reason)
        assertFalse(session.peered)
    }
}
