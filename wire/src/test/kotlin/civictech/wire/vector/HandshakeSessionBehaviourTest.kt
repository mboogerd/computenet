package civictech.wire.vector

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
import civictech.cell.wire.WireCodec
import civictech.wire.WsTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/**
 * The session behaviour around the legacy HELLO line — feature computenet-ncz.5
 * (WIR1-F5) task 3, B2.1–B2.5, per epic decision D7: "the HELLO text is a
 * vector; the session behaviour is a conventional `:wire` test citing
 * `[WIR1-I12]`–`[WIR1-I15]`".
 *
 * Every scenario drives a real [WsTransport.Session] directly — no socket, no
 * `WsListener`/`WsConnection` (the socket-level coverage is cited per test) —
 * with the bridge host on a [SimulationController], the scaffold of
 * `WsClientReconnectFenceTest`. The Session's `send` sink is a list, so what
 * the session puts on the wire is read as exactly that list.
 *
 * **Announcement hooks are counted as frames (ncz.5-D7).** `Peering.announceTo`
 * registers `onLocalPublish` (and siblings) on the registry and pushes each
 * announcement through a `HostedCellProxy` over the Session's egress into
 * `send`. So the number of live announcement hooks is the number of frames one
 * local `registry.publish` puts on `send`, and which hook is live is the
 * decoded frame's `cellRef` (the peer mirror it announces to). The Session's
 * private `announcement` field is never reflected on.
 */
class HandshakeSessionBehaviourTest {

    /**
     * The far side of the wire: the exact bytes a peer would send to announce
     * `published` to the mirror at `toMirror` — copied from
     * `WsClientReconnectFenceTest.Peer` (a [BridgeEgressCell] fronting an
     * announce proxy, the socket replaced by a list).
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

    /** One Session over a simulated bridge host, with its `send` and `refuse` observed. */
    private class Rig(peer: PeerId?, allow: Set<PeerId>? = null) {
        val controller = SimulationController(14)
        val registry = LocationRegistry()
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = peer, allow = allow)
        val sent = mutableListOf<ByteArray>()
        var refusals = 0
        val session = WsTransport.Session(side, send = { sent += it }, refuse = { refusals++ })

        /** One fresh local publish; returns the frames it put on `send` (the live announcement hooks). */
        fun framesForOnePublish(): List<ByteArray> {
            controller.runToIdle()
            sent.clear()
            registry.publish(CellRef(UUID.randomUUID()), bridgeHost)
            controller.runToIdle()
            return sent.toList()
        }
    }

    /** The mirror ref a hello line offers (the token after `"HELLO "`). */
    private fun mirrorOffered(hello: String): UUID =
        UUID.fromString(hello.removePrefix("HELLO ").substringBefore(" "))

    private val loader = VectorLoader.locate()

    private fun committed(id: String): VectorDocument = loader.documents().first { it.id == id }

    /** The committed vector's pinned `decoded.fields.mirrorRef` — read from the corpus, never a Kotlin literal. */
    private fun pinnedMirrorRef(doc: VectorDocument): String {
        val fields = (doc.decoded as JsonObject)["fields"] as JsonObject
        return (fields["mirrorRef"] as JsonPrimitive).content
    }

    /** An in-memory `handshake-text` HELLO document for an arbitrary mirror ref, parsed by the corpus parser. */
    private fun helloDoc(mirrorRef: UUID, peerName: String?): VectorDocument {
        val line = "in-memory" // encoded is not read by HandshakeLines.lineOf; it denotes from `decoded` only
        val obj = buildJsonObject {
            put("id", "WV-HELLO-SESSION-00")
            put("title", "HandshakeSessionBehaviourTest in-memory document")
            put("category", "handshake")
            put("kind", "handshake-text")
            putJsonArray("covers") { add(JsonPrimitive("WIR1-I12")) }
            put("codecVersion", 2)
            put("messageKind", "text")
            putJsonObject("decoded") {
                put("type", "HELLO")
                putJsonObject("fields") {
                    put("mirrorRef", mirrorRef.toString())
                    if (peerName != null) put("peerName", peerName)
                }
            }
            putJsonObject("encoded") {
                put("utf8", line)
                put("base64", Base64.getEncoder().encodeToString(line.toByteArray(Charsets.UTF_8)))
            }
            put("notes", "built in HandshakeSessionBehaviourTest")
        }
        return VectorDocument.parse(obj, loader.root.resolve("handshake/WV-HELLO-SESSION-00.json"), loader.root)
    }

    /**
     * Shared body of B2.1/B2.2. **Two-step, stated honestly (feature design
     * E1):** the mirror ref is minted by the spawn inside `hello()`, so the test
     * cannot choose it. It therefore (1) proves the ref in the line IS the
     * mirror this open spawned (in `registry.localRefs()`), (2) compares the
     * line to what the corpus grammar denotes for THAT ref, and (3) compares it,
     * with the ref substituted by the committed vector's pinned `mirrorRef`, to
     * the vector's `encoded.utf8` byte for byte.
     *
     * "First outbound message": the callers send `hello()`'s return before
     * anything else — `WsTransport.WsListener.onOpen` (`conn.send(session.hello())`)
     * and `WsTransport.WsConnection`'s client `onOpen` (`send(session.hello())`);
     * binary frames only follow admission. Asserted here as `send` being empty
     * when the line is produced.
     */
    private fun assertHelloIsTheVector(peer: PeerId?, vectorId: String) {
        val rig = Rig(peer)
        val line = rig.session.hello()
        rig.controller.runToIdle()

        assertTrue(rig.sent.isEmpty(), "no binary frame may leave `send` before the hello; got ${rig.sent.size}")

        val mirrorRef = mirrorOffered(line)
        // (a) the ref in the line is the mirror this open spawned, not an arbitrary uuid
        assertTrue(CellRef(mirrorRef) in rig.registry.localRefs(), "hello offers $mirrorRef, which is not a local ref")
        // (b) the session's bytes are what the corpus grammar denotes
        assertEquals(HandshakeLines.lineOf(helloDoc(mirrorRef, peer?.name)), line)
        // (c) with the spawned ref replaced by the pinned one, the committed vector
        val vector = committed(vectorId)
        assertEquals(vector.encoded!!.utf8, line.replace(mirrorRef.toString(), pinnedMirrorRef(vector)))
        // (d) one TEXT message, no terminator, no trailing space
        assertFalse(line.endsWith(" "), "trailing space in '$line'")
        assertFalse('\n' in line || '\r' in line, "terminator in '$line'")
    }

    /**
     * `[WIR1-I12]`, `[WIR1-C07]`, B2.1 — a credential-less Session whose side is
     * named `alpha` opens with exactly `WV-HELLO-NAMED-01`'s line (modulo the
     * spawned mirror ref). `HandshakeLinesTest` covers the builder alone; this
     * ties it to the real `Session.hello()`.
     */
    @Test
    fun `B2_1 a named session's hello is WV-HELLO-NAMED-01 for the mirror it spawned`() =
        assertHelloIsTheVector(PeerId("alpha"), "WV-HELLO-NAMED-01")

    /**
     * `[WIR1-I12]`, `[WIR1-C07]`, B2.2 — an anonymous (`peer = null`) Session
     * opens with `WV-HELLO-UNNAMED-01`'s line: no name token and no trailing
     * space.
     */
    @Test
    fun `B2_2 an anonymous session's hello is WV-HELLO-UNNAMED-01 for the mirror it spawned`() =
        assertHelloIsTheVector(null, "WV-HELLO-UNNAMED-01")

    /**
     * `[WIR1-I13]`, B2.3 — before an admitted hello, a VALID announcement frame
     * (addressed to the very mirror this open offered) is dropped and enters
     * nothing.
     *
     * Complements `WsTransportPreHelloDropTest`, which carries the per-frame
     * count: it asserts `preHelloDrops` 0→1→2 for two frames and unchanged
     * after hello — but its bytes are `1,2,3`, so it does not show that a frame
     * which WOULD install a Remote is kept out of the graph. This does.
     */
    @Test
    fun `B2_3 a valid frame before any admitted hello is dropped and installs nothing`() {
        val rig = Rig(PeerId("jvm-a"))
        val offered = CellRef(mirrorOffered(rig.session.hello()))
        rig.controller.runToIdle()

        val ref = CellRef(UUID.randomUUID())
        rig.session.onFrame(Peer().announces(ref, toMirror = offered))
        rig.controller.runToIdle()

        assertEquals(1L, rig.session.preHelloDrops)
        assertEquals(0L, rig.session.framesReceived)
        assertNull(rig.registry.location(ref))
        assertFalse(ref in rig.registry.remoteRefs())
    }

    /**
     * `[WIR1-I14]`, B2.4 — a hello naming a peer the side's `allow` set refuses
     * is refused at the Session: no auth level, no announcement hook (a local
     * publish puts zero frames on `send`), no ingress (a later valid frame is a
     * pre-hello drop), and the Session's `refuse` callback — the connection
     * close — fires once (`admitted`'s false branch calls `refuse()`, verified in
     * `WsTransport.kt`).
     *
     * Complements the existing coverage: `WsAdmissionDenialTest` (socket-level
     * accounting — `listener.admissionDenialCount` counts the refused hello) and
     * `kernel`'s `TrustBoundaryTest` (the `Peering.Side.admits` allowlist
     * itself).
     */
    @Test
    fun `B2_4 a hello from a peer outside the allow set installs neither announcer nor ingress`() {
        val rig = Rig(PeerId("server"), allow = setOf(PeerId("good")))
        val offered = CellRef(mirrorOffered(rig.session.hello()))
        rig.session.onText("HELLO ${UUID.randomUUID()} mallory")
        rig.controller.runToIdle()

        assertNull(rig.session.achievedAuthLevel)
        assertEquals(1, rig.refusals, "the refused hello closes the connection once")
        assertEquals(0, rig.framesForOnePublish().size, "a refused peer must have no announcement hook")

        val ref = CellRef(UUID.randomUUID())
        rig.session.onFrame(Peer().announces(ref, toMirror = offered))
        rig.controller.runToIdle()
        assertEquals(1L, rig.session.preHelloDrops)
        assertEquals(0L, rig.session.framesReceived)
        assertNull(rig.registry.location(ref))
    }

    /**
     * Shared body of B2.5: two admitted hellos on ONE Session, then one local
     * publish. The count of frames is the count of live announcement hooks;
     * the decoded `cellRef` names which one survived.
     */
    private fun assertReHelloSupersedes(closeBetween: Boolean) {
        val rig = Rig(PeerId("jvm-a"))
        val m1 = UUID.randomUUID()
        val m2 = UUID.randomUUID()

        rig.session.hello()
        rig.session.onText("HELLO $m1 jvm-b")
        rig.controller.runToIdle()
        if (closeBetween) rig.session.onClose()

        rig.session.hello()
        rig.session.onText("HELLO $m2 jvm-b")
        rig.controller.runToIdle()

        val frames = rig.framesForOnePublish()
        assertEquals(1, frames.size, "exactly one announcement hook may survive a re-hello")
        assertEquals(CellRef(m2), WireCodec.decodeFrame(frames.single()).frame.cellRef)
    }

    /**
     * `[WIR1-I15]`, B2.5 — a re-hello after `onClose` (the client reconnect
     * shape: `WsConnection` keeps one Session across reconnects) leaves exactly
     * one announcement hook, the second connection's.
     *
     * Adjacent, not this property: `WsClientReconnectFenceTest` (the mirror
     * fence on the same one-Session shape — a stale frame installs nothing) and
     * `WsReconnectSmokeTest` (socket-level re-hello). `ChainOnReannounceTest` is
     * NOT this property: it counts app-side link catch-ups, not announcement
     * hooks.
     */
    @Test
    fun `B2_5 a re-hello after close leaves exactly the second connection's announcer`() =
        assertReHelloSupersedes(closeBetween = true)

    /**
     * `[WIR1-I15]`, B2.5 — the same on a kept socket with no `onClose` between
     * the hellos: `hello()`/`bindAndAnnounce` supersede by themselves
     * (`announcement?.close()` before the new `Peering.announceTo`); `onClose`
     * is not required for the old hook to go.
     */
    @Test
    fun `B2_5 a re-hello on a kept session without close also leaves exactly one announcer`() =
        assertReHelloSupersedes(closeBetween = false)
}
