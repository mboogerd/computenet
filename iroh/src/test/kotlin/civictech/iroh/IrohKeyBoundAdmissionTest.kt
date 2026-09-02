package civictech.iroh

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.control.Attention
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.AuthLevel
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.membrane.Principal
import civictech.cell.membrane.currentPrincipal
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.Peering
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import civictech.identity.Ed25519
import civictech.identity.fingerprint
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Feature `computenet-egl.3` end to end, over two real sidecars and one real
 * QUIC link (task `computenet-egl.3.3`). [IrohSessionHelloTest] proves the same
 * three properties at the [IrohTransport.Session] level with no sidecar; this
 * file proves them where a peer actually is — behind a QUIC handshake the
 * sidecar performed and a NodeId nobody in this JVM chose.
 *
 * The three cases are the feature's three examples:
 *
 * 1. **Admission is a public-key allowlist, and the stamp comes from the
 *    binding.** L allowlists the [KeyId] fingerprinted from B's NodeId; B's
 *    invocation is delivered and a cell on L reading
 *    [civictech.cell.membrane.currentPrincipal] inside that delivery observes
 *    `Principal.Peer(L.side.identityBinding.identityOf(key), Authenticated)`.
 *    The expected identity is spelled *through the binding* — writing it as
 *    `PeerId(fingerprint(...).name)` would make the assertion a restatement of
 *    the implementation rather than a check on it (feature `computenet-376c`).
 *
 *    The level asserted is [AuthLevel.Authenticated] because that is what
 *    [IrohTransport]'s class KDoc documents and argues for: "iroh's QUIC/TLS
 *    1.3 handshake is a **proof of possession** of the NodeId key, bound to
 *    this connection instance", resting on the stated assumption that "the
 *    sidecar is part of this side's trusted computing base".
 *
 * 2. **An unlisted NodeId is refused before any delivery lands.** Nothing the
 *    refused side published reaches L's registry, the denial is accounted, and
 *    the refused dialler gives up rather than looping (computenet-4gzr).
 *
 * 3. **The self-assertion defect, closed over the wire.** A raw sidecar client
 *    whose NodeId is *not* allowlisted sends a hello asserting the admitted
 *    peer's exact identity string. Over the interim (egl.2-D4) transport that
 *    line WAS the admission token and would have been admitted. Now the token
 *    can only ever confirm the identity the connection's key resolves to, so
 *    the forger is refused, its link closed, and it never hears a hello back.
 *    `IrohSessionHelloTest` pins the refusal *reason*
 *    ([civictech.cell.DenialReason.ID_MISMATCH]), which is observable only at
 *    Session level; here the observable outcome is the count, the closed link
 *    and the untouched registry.
 *
 * Every wait is bounded ([await], [neverWithin], [quiesced] — copied from
 * [IrohPeeringTest] rather than imported, since this file may not modify it);
 * no fixed sleep is evidence for anything. Every sidecar is closed in `use`.
 *
 * Skip-gated: without `-Piroh.enabled=true` (hence without a built sidecar)
 * every test here reports SKIPPED, never failed — see [SidecarBinary].
 */
class IrohKeyBoundAdmissionTest {

    // ---------------------------------------------------------------- probes

    /**
     * Records the ambient [Principal] of every attention assertion it is handed
     * — the [civictech.wire.WsPrincipalPromotionTest] probe shape, redeclared
     * here because `:iroh`'s test classpath carries `:testkit` but not `:wire`'s
     * or `:kernel`'s test sources.
     *
     * A `PORT_PROTOCOL` delivery is used because it is the one wire-encodable
     * delivery type whose dispatch `ManagedHost` runs under the stamped peer.
     */
    class PrincipalProbeCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val principals = CopyOnWriteArrayList<Principal>()

        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            ProtocolSupport.of(outlet).handle(Protocols.Attention) { _, _ ->
                principals += currentPrincipal()
            }
        }
    }

    /**
     * A [LinkListener] for the raw forger in case 3. [RecordingLinkListener]
     * would do for the `LINK_DOWN` half, but this file may not add the
     * `noDataWithin` absence-probe that case needs to it, so the whole listener
     * is local.
     */
    private class ForgerLinkListener : LinkListener {
        private val data = LinkedBlockingQueue<ByteArray>()
        private val downs = LinkedBlockingQueue<String>()
        val downCount = AtomicInteger(0)
        val errors = CopyOnWriteArrayList<String>()

        override fun onData(link: SidecarLink, payload: ByteArray) {
            data.put(payload)
        }

        override fun onDown(link: SidecarLink, reason: String) {
            downCount.incrementAndGet()
            downs.put(reason)
        }

        override fun onError(link: SidecarLink, reason: String) {
            errors.add(reason)
        }

        /** The next `LINK_DOWN` reason, or a test failure after [seconds]. */
        fun nextDown(seconds: Long = 30): String =
            downs.poll(seconds, TimeUnit.SECONDS) ?: fail("forger: no LINK_DOWN within ${seconds}s")

        /** True when NO `DATA` arrived within [millis] — the absence case 3 pins. */
        fun noDataWithin(millis: Long = 3_000): Boolean =
            data.poll(millis, TimeUnit.MILLISECONDS) == null
    }

    // ---------------------------------------------------------------- fixture

    private class Stack(name: String? = null, allow: Set<KeyId>? = null) {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = name?.let { PeerId(it) }, allow = allow)
    }

    /** A freshly generated 64-hex secret key, to pin a sidecar's NodeId across spawns. */
    private fun pinnedSecretKeyArgs(): List<String> {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        return listOf("--secret-key", secret)
    }

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

    /** Reads [value] only once it has stopped changing for [settleMillis] (computenet-6lam). */
    private fun quiesced(settleMillis: Long = 1_500, timeoutMs: Long = 30_000, value: () -> Long): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = value()
        var lastChangedAt = System.currentTimeMillis()
        while (true) {
            Thread.sleep(50)
            val now = value()
            val time = System.currentTimeMillis()
            if (now != last) {
                last = now
                lastChangedAt = time
            } else if (time - lastChangedAt >= settleMillis) {
                return last
            }
            if (time > deadline) fail("timed out waiting for value to quiesce (stuck at $last)")
        }
    }

    /** One `PORT_PROTOCOL` attention assertion aimed at [target]'s `outlet`. */
    private fun protocolFrame(target: CellRef) = HostedPortInvocation(
        cellRef = target,
        portName = "outlet",
        type = HostedPortInvocation.Type.PORT_PROTOCOL,
        invocation = Invocation("", emptyList(), emptyList()),
        protocolId = Protocols.Attention,
        protocolLink = WireEdgeLink(
            id = UUID.randomUUID(),
            from = PortRef.generate(),
            to = PortRef.generate(target),
            fromAddr = PortAddress(CellRef(UUID.randomUUID()), "inlet"),
            toAddr = PortAddress(target, "outlet"),
        ),
        protocolMessage = Attention(1f),
    )

    private fun stderrSink(label: String): (String) -> Unit = { line -> println("[iroh-stderr $label] $line") }

    // ------------------------------------------------------------- example 1

    @Test
    fun `an allowlisted NodeId is admitted and its deliveries are stamped with the binding-resolved identity`() {
        val binary = SidecarBinary.orSkip()

        // B's NodeId has to be known before the listener exists, because the
        // allowlist judges the KEY the QUIC connection authenticates. Pin B's
        // sidecar secret key, spawn once to read the NodeId it yields, and dial
        // later with the same args so the endpoint is the same endpoint.
        val bArgs = pinnedSecretKeyArgs()
        val nodeIdB = SidecarProcess.spawn(binary, args = bArgs).use { it.nodeId }
        val keyB = fingerprint(Ed25519.publicKeyFromRaw(nodeIdB))

        val l = Stack(name = "listener", allow = setOf(keyB))
        // The expected identity, spelled through the binding this side actually
        // consults — NOT as `PeerId(keyB.name)`, which would assert the interim
        // binding's shape rather than that the site resolves through it.
        val expectedIdentity = l.side.identityBinding.identityOf(keyB)

        // B carries a name of its own, and that name is NOT what gets stamped:
        // over this transport `Side.peer` is not written to the wire at all.
        val b = Stack(name = "bob-says-so")

        IrohTransport.listen(l.side, binary, stderrSink = stderrSink("listener")).use { listener ->
            val probe = PrincipalProbeCell()
            l.host.managementInlet.call.spawn(probe)

            IrohTransport.connect(
                b.side,
                listener.nodeId,
                listener.addresses,
                binary,
                stderrSink = stderrSink("bob"),
                sidecarArgs = bArgs,
            ).use { connection ->
                await("the admitted dialler learns the listening side's probe") {
                    b.registry.location(probe.ref) is LocationRegistry.Remote
                }
                assertTrue(connection.peered, "an allowlisted NodeId must be admitted")

                // Routed by B's own registry, so it takes this connection's
                // egress: encoded here, decoded and stamped by L's
                // `BridgeIngressCell`, dispatched by L's host.
                b.registry.deliver(protocolFrame(probe.ref))
                await("the assertion crossed the QUIC link and was dispatched on L") {
                    probe.principals.isNotEmpty()
                }

                assertEquals(
                    Principal.Peer(expectedIdentity, AuthLevel.Authenticated),
                    probe.principals.last(),
                    "a delivery over an iroh crossing admitted on its NodeId-derived key must be stamped with " +
                        "the identity the side's binding resolves for that key, at the level IrohTransport's " +
                        "KDoc argues for (proof of possession of the NodeId key, bound to this connection)",
                )
                assertEquals(0L, listener.admissionDenialCount, "an allowlisted peer costs no denial")
                assertEquals(0L, listener.preHelloDrops, "no frame arrived before an admitted hello")
                assertTrue(listener.linkErrors.isEmpty(), "sidecar reported link errors: ${listener.linkErrors}")
            }
        }
    }

    // ------------------------------------------------------------- example 2

    @Test
    fun `a NodeId off the allowlist is refused before any delivery reaches the listening registry`() {
        val binary = SidecarBinary.orSkip()

        // Somebody is on the allowlist — so the refusal below is a decision
        // about mallory's key, not a side that refuses everyone.
        val goodArgs = pinnedSecretKeyArgs()
        val goodNodeId = SidecarProcess.spawn(binary, args = goodArgs).use { it.nodeId }
        val l = Stack(name = "listener", allow = setOf(fingerprint(Ed25519.publicKeyFromRaw(goodNodeId))))

        IrohTransport.listen(l.side, binary, stderrSink = stderrSink("listener")).use { listener ->
            val published = SetCell<String>()
            l.host.managementInlet.call.spawn(published)

            // mallory's sidecar key is fresh and unpinned, so its NodeId cannot
            // be on any allowlist written before it existed.
            val mallory = Stack(name = "mallory")
            val malloryPublished = SetCell<String>()
            mallory.host.managementInlet.call.spawn(malloryPublished)

            // connect() returns normally — a denial is not a fault (BS-14).
            IrohTransport.connect(
                mallory.side,
                listener.nodeId,
                listener.addresses,
                binary,
                stderrSink = stderrSink("mallory"),
                // Near-zero backoff so the refused-dial bound is reached inside
                // this test rather than in minutes.
                backoff = { 10L },
            ).use { refused ->
                await("the refused hello is accounted on the listener's admission sink") {
                    listener.admissionDenialCount >= 1L
                }
                assertFalse(refused.peered, "a refused peer installs no ingress on its own side either")
                assertTrue(
                    neverWithin { mallory.registry.location(published.ref) is LocationRegistry.Remote },
                    "a refused peering must apply no announcement",
                )
                assertNull(
                    l.registry.location(malloryPublished.ref),
                    "the refusing side installed no ingress, so nothing mallory published could arrive",
                )

                // The dialler is not told it was refused (PROTOCOL.md carries no
                // reason), so it re-dials — but boundedly, and it says so
                // (computenet-4gzr).
                await("the refused dialler gives up rather than looping forever") {
                    refused.abandonedAfterRefusals
                }
                val settled = quiesced { listener.admissionDenialCount }
                assertTrue(
                    settled >= 1L,
                    "at least one refusal must be accounted, and the count must settle rather than grow",
                )
                // `unadmittedOpens` is read the same way `admissionDenialCount`
                // is above: quiesced rather than sampled once. IrohTransport's
                // own KDoc on REFUSED_DIAL_LIMIT documents that at most one
                // further link can be in flight when `abandoned` is observed —
                // dialled by a re-dial loop iteration that wins its race
                // against the LINK_DOWN which would have stopped it — so a
                // single read taken right after `abandonedAfterRefusals` can
                // still be one *link* short of what that in-flight link is
                // about to add. Short is the only direction it can be wrong
                // in: the run counter is cleared only by an admitted link or
                // an explicit `heal()`, and a refused peering reaches
                // neither, so the value never decreases under this read.
                // Quiescing removes the guesswork: once the value stops
                // changing for `settleMillis`, nothing further is coming, and
                // the bound below is checked against a number the production
                // code has actually finished producing, not one caught
                // mid-flight.
                val settledUnadmitted = quiesced { refused.unadmittedOpens.toLong() }
                assertTrue(
                    settledUnadmitted >= IrohTransport.REFUSED_DIAL_LIMIT,
                    "abandonedAfterRefusals was observed, so at least REFUSED_DIAL_LIMIT " +
                        "(${IrohTransport.REFUSED_DIAL_LIMIT}) consecutive unadmitted opens must have been " +
                        "counted; settled at $settledUnadmitted",
                )
                assertTrue(
                    settledUnadmitted <= IrohTransport.REFUSED_DIAL_LIMIT + 1,
                    "the refused dialler settled at $settledUnadmitted unadmitted links, past the bound " +
                        "IrohTransport.REFUSED_DIAL_LIMIT (${IrohTransport.REFUSED_DIAL_LIMIT}) plus the one " +
                        "re-dial that may be in flight when abandonment is decided — and this is the settled, " +
                        "not a mid-flight, value",
                )
                assertFalse(refused.peered, "a dialler that gave up is still not peered")
            }
            // `use` closed the refused connection: close() returned normally,
            // nothing thrown, and the listening registry is still untouched.
            assertNull(
                l.registry.location(malloryPublished.ref),
                "closing a refused connection must not deliver anything after the fact",
            )
        }
    }

    // ------------------------------------------------------------- example 3

    @Test
    fun `a raw client asserting the admitted peer's identity string in its hello is refused`() {
        val binary = SidecarBinary.orSkip()

        // The identity a forger would want: the one L resolves for the
        // allowlisted peer's key. It is spelled through the binding, so the
        // forged token is exactly the string a successful impersonation would
        // have to produce.
        val goodArgs = pinnedSecretKeyArgs()
        val goodNodeId = SidecarProcess.spawn(binary, args = goodArgs).use { it.nodeId }
        val goodKey = fingerprint(Ed25519.publicKeyFromRaw(goodNodeId))
        val l = Stack(name = "listener", allow = setOf(goodKey))
        val admittedName = l.side.identityBinding.identityOf(goodKey).name

        IrohTransport.listen(l.side, binary, stderrSink = stderrSink("listener")).use { listener ->
            val published = SetCell<String>()
            l.host.managementInlet.call.spawn(published)
            val denialsBefore = listener.admissionDenialCount

            // A RAW sidecar client: no `IrohTransport`, no `Peering.Side` — just
            // the bytes an attacker would write. Its NodeId is fresh, hence not
            // allowlisted, and it is not the key `admittedName` belongs to.
            SidecarProcess.spawn(binary, stderrSink = stderrSink("forger")).use { forgerSidecar ->
                forgerSidecar.connect().use { forgerHost ->
                    forgerHost.addPeer(listener.nodeId, listener.addresses)
                    val listenerOnLink = ForgerLinkListener()
                    val link = forgerHost.dial(listener.nodeId, listenerOnLink)

                    // The forged hello: a well-formed mirror ref, and the
                    // admitted peer's exact identity string as the trailing
                    // token. Under egl.2-D4's interim this token WAS the
                    // admission key and this line would have been admitted.
                    val forged = IrohTransport.HELLO_PREFIX + UUID.randomUUID() + " " + admittedName
                    link.send(forged.toByteArray(StandardCharsets.UTF_8))

                    // Refused: the link is closed by the listening side...
                    listenerOnLink.nextDown()
                    assertEquals(1, listenerOnLink.downCount.get(), "the refusal closes the link exactly once")
                    // ...and no hello ever came back, because a refused hello
                    // makes this side mint no mirror and write nothing at all.
                    assertTrue(
                        listenerOnLink.noDataWithin(),
                        "a refused forger must receive no hello back — the listening side writes nothing to a " +
                            "peer it will not talk to",
                    )
                }
            }

            // Exactly one denial for this one link, and nothing of the forger's
            // in L's registry — no ingress was ever installed for it.
            val settled = quiesced { listener.admissionDenialCount }
            assertEquals(
                denialsBefore + 1L,
                settled,
                "the forged hello must be accounted exactly once: the token can only CONFIRM the identity the " +
                    "connection's key resolves to, never supply one (IrohSessionHelloTest pins the reason, " +
                    "DenialReason.ID_MISMATCH, at Session level)",
            )
            assertEquals(0L, listener.preHelloDrops, "the forged frame was read as a hello, not dropped after one")
            assertTrue(
                l.registry.location(published.ref) is LocationRegistry.Local,
                "L's own published ref is still local — a refused forger installs no mirror over it",
            )
            assertTrue(
                neverWithin { l.registry.remoteRefs().isNotEmpty() },
                "L's registry gained no Remote location from a refused forger",
            )
        }
    }
}
