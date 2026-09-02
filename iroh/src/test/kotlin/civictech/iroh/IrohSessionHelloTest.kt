package civictech.iroh

import civictech.cell.DenialReason
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.link.PeerIdentityBinding
import civictech.cell.wire.Peering
import civictech.identity.Ed25519
import civictech.identity.fingerprint
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
 *
 * Since feature `computenet-egl.3` a Session is constructed with the remote
 * NodeId of its link, and the admission key is derived from *that* — so these
 * tests mint real ed25519 keypairs and hand their raw public halves in as
 * NodeIds ([nodeId]). A hello token is never an admission token here; the only
 * thing it can do is disagree.
 */
class IrohSessionHelloTest {

    /** A fresh, valid 32-byte iroh NodeId, and the key identifier it derives. */
    private fun nodeId(): ByteArray = Ed25519.rawPublicKey(Ed25519.generateKeyPair().public)

    private fun keyOf(nodeId: ByteArray): KeyId = fingerprint(Ed25519.publicKeyFromRaw(nodeId))

    private fun side(
        name: String? = null,
        allow: Set<KeyId>? = null,
        binding: PeerIdentityBinding = PeerIdentityBinding.Interim,
    ): Peering.Side {
        val registry = LocationRegistry()
        return Peering.Side(
            registry,
            ManagedHost(registry = registry),
            peer = name?.let { PeerId(it) },
            allow = allow,
            identityBinding = binding,
        )
    }

    private fun hello(mirrorRef: UUID = UUID.randomUUID(), name: String? = null): ByteArray =
        (IrohTransport.HELLO_PREFIX + mirrorRef + (name?.let { " $it" } ?: "")).toByteArray(StandardCharsets.UTF_8)

    @Test
    fun `frames after a refused hello are dropped and counted, never routed`() {
        val sent = mutableListOf<ByteArray>()
        var refusals = 0
        val session = IrohTransport.Session(side(), nodeId(), send = { sent += it }, refuse = { refusals++ })

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
    fun `an admitted hello mints a mirror, answers with a nameless hello, and installs the ingress`() {
        val sent = mutableListOf<ByteArray>()
        val remote = nodeId()
        val local = side(name = "local")
        val session = IrohTransport.Session(local, remote, send = { sent += it }, refuse = { })

        // No token at all: the connection's NodeId is the whole admission story.
        session.onData(hello())

        assertTrue(session.peered, "an admitted hello installs the ingress")
        assertEquals(0L, session.preHelloDrops)
        assertEquals(0L, session.admissionDenialCount)
        // The identity stamped on the mirror is the binding's, resolved from the
        // NodeId-derived key — not the hello, which named nobody.
        assertEquals(
            local.identityBinding.identityOf(keyOf(remote)),
            assertNotNull(session.mirrorCell).peer,
            "the mirror carries the identity this side's binding resolved for the link's key",
        )
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
        assertEquals(
            IrohTransport.HELLO_PREFIX + assertNotNull(session.mirrorRef).id,
            answer,
            "our hello is exactly the prefix and this link instance's mirror ref — no name token, " +
                "even though this side's `peer` is set",
        )
    }

    @Test
    fun `admission is decided on the link's NodeId, and an allowlisted name cannot be asserted onto it`() {
        val goodNodeId = nodeId()
        val goodKey = keyOf(goodNodeId)
        val allow = setOf(goodKey)

        // ---- the holder of the allowlisted key is admitted -----------------
        val admitted = IrohTransport.Session(
            side(name = "server", allow = allow),
            goodNodeId,
            send = { },
            refuse = { throw AssertionError("the holder of an allowlisted key must not be refused") },
        )
        admitted.onData(hello())
        assertTrue(admitted.peered)
        assertEquals(0L, admitted.admissionDenialCount)

        // ---- a different key is refused, whatever it calls itself ----------
        val sent = mutableListOf<ByteArray>()
        var refusals = 0
        val malloryNodeId = nodeId()
        val malloryServerSide = side(name = "server", allow = allow)
        val mallory = IrohTransport.Session(
            malloryServerSide,
            malloryNodeId,
            send = { sent += it },
            refuse = { refusals++ },
        )

        mallory.onData(hello())

        assertEquals(1, refusals, "the link is closed")
        assertEquals(1L, mallory.admissionDenialCount)
        val denial = assertNotNull(mallory.lastAdmissionDenial)
        assertEquals(DenialReason.NOT_ADMITTED, denial.reason)
        assertEquals(
            malloryServerSide.identityBinding.identityOf(keyOf(malloryNodeId)),
            denial.principal,
            "the refusal is attributed to the identity of the key that actually dialled",
        )
        assertFalse(mallory.peered, "no ingress on a refused hello")
        assertEquals(null, mallory.mirrorRef, "a refused peer costs this side no mirror")
        assertTrue(sent.isEmpty(), "nothing is written to a refused link — not even our hello")

        // ---- and asserting the allowlisted NAME buys nothing ---------------
        // The self-assertion defect, closed: this is the same unlisted key, now
        // claiming to be the admitted peer. It is refused BEFORE the allowlist
        // ever sees it, on the disagreement itself.
        val forgedSent = mutableListOf<ByteArray>()
        var forgedRefusals = 0
        val forgingSide = side(name = "server", allow = allow)
        val forgingNodeId = nodeId()
        val forging = IrohTransport.Session(
            forgingSide,
            forgingNodeId,
            send = { forgedSent += it },
            refuse = { forgedRefusals++ },
        )

        forging.onData(hello(name = goodKey.name))

        assertEquals(1, forgedRefusals)
        val forgedDenial = assertNotNull(forging.lastAdmissionDenial)
        assertEquals(
            DenialReason.ID_MISMATCH,
            forgedDenial.reason,
            "a token that disagrees with the connection's own key is a mismatch, not an admission",
        )
        assertEquals(
            forgingSide.identityBinding.identityOf(keyOf(forgingNodeId)),
            forgedDenial.principal,
            "the denial names who was actually on the link, not who they claimed to be",
        )
        assertTrue(
            assertNotNull(forgedDenial.detail).contains(goodKey.name),
            "the detail records the claim: ${forgedDenial.detail}",
        )
        assertFalse(forging.peered)
        assertEquals(null, forging.mirrorRef)
        assertTrue(forgedSent.isEmpty())
    }

    @Test
    fun `a token equal to the resolved identity is redundant and admitted`() {
        val remote = nodeId()
        val local = side(name = "local")
        val resolved = local.identityBinding.identityOf(keyOf(remote))
        val session = IrohTransport.Session(
            local,
            remote,
            send = { },
            refuse = { throw AssertionError("a confirming token must not be refused") },
        )

        session.onData(hello(name = resolved.name))

        assertTrue(session.peered, "a token that agrees with the derivation confirms it")
        assertEquals(0L, session.admissionDenialCount)
    }

    @Test
    fun `the stamped and denied identity comes from the side's binding, never from the key itself`() {
        val aliasing = PeerIdentityBinding { key -> PeerId("alias-of-" + key.name) }
        val remote = nodeId()
        val expected = PeerId("alias-of-" + keyOf(remote).name)

        // Admitted path: the ALIAS is what the mirror carries. A site that wrote
        // `PeerId(key.name)` — or fingerprinted its way to an identity — stamps
        // the key identifier here instead, and this fails.
        val open = IrohTransport.Session(
            side(name = "local", binding = aliasing),
            remote,
            send = { },
            refuse = { throw AssertionError("an open side must admit a valid key") },
        )
        open.onData(hello())
        assertTrue(open.peered)
        assertEquals(expected, assertNotNull(open.mirrorCell).peer)

        // Refused path: the same alias is what the denial is attributed to.
        var refusals = 0
        val closed = IrohTransport.Session(
            side(name = "local", allow = setOf(KeyId("nobody")), binding = aliasing),
            remote,
            send = { },
            refuse = { refusals++ },
        )
        closed.onData(hello())
        assertEquals(1, refusals)
        val denial = assertNotNull(closed.lastAdmissionDenial)
        assertEquals(DenialReason.NOT_ADMITTED, denial.reason)
        assertEquals(expected, denial.principal)

        // Mismatch path: the alias is what a claimed token is compared against,
        // so the key identifier's own name is now a FOREIGN claim.
        var mismatchRefusals = 0
        val mismatching = IrohTransport.Session(
            side(name = "local", binding = aliasing),
            remote,
            send = { },
            refuse = { mismatchRefusals++ },
        )
        mismatching.onData(hello(name = keyOf(remote).name))
        assertEquals(1, mismatchRefusals)
        assertEquals(DenialReason.ID_MISMATCH, assertNotNull(mismatching.lastAdmissionDenial).reason)
    }

    @Test
    fun `an open side admits any valid key`() {
        val session = IrohTransport.Session(
            side(),
            nodeId(),
            send = { },
            refuse = { throw AssertionError("an open side refuses nobody") },
        )
        session.onData(hello())
        assertTrue(session.peered)
        assertEquals(0L, session.admissionDenialCount)
    }

    @Test
    fun `a NodeId that is not a valid key is refused as malformed before the allowlist is consulted`() {
        var refusals = 0
        // 32 bytes that are not a valid Edwards point encoding. An allowlist is
        // present and would refuse this connection anyway — the point is that it
        // is never reached, so the denial names the shape and no principal.
        val notAPoint = ByteArray(32) { 0xFF.toByte() }
        val session = IrohTransport.Session(
            side(name = "server", allow = setOf(KeyId("good"))),
            notAPoint,
            send = { },
            refuse = { refusals++ },
        )

        session.onData(hello())

        assertEquals(1, refusals)
        val denial = assertNotNull(session.lastAdmissionDenial)
        assertEquals(DenialReason.MALFORMED_HELLO, denial.reason)
        assertEquals(null, denial.principal, "no key means no identity to attribute the refusal to")
        assertFalse(session.peered)
        assertTrue(
            assertNotNull(denial.detail).contains("NodeId"),
            "the detail names the shape: ${denial.detail}",
        )
    }

    @Test
    fun `a hello whose first token is not a mirror ref is refused as malformed`() {
        var refusals = 0
        val session = IrohTransport.Session(side(), nodeId(), send = { }, refuse = { refusals++ })

        session.onData((IrohTransport.HELLO_PREFIX + "not-a-uuid").toByteArray(StandardCharsets.UTF_8))

        assertEquals(1, refusals)
        assertEquals(DenialReason.MALFORMED_HELLO, assertNotNull(session.lastAdmissionDenial).reason)
        assertFalse(session.peered)
    }
}
