package civictech.wire

import civictech.cell.CellRef
import civictech.cell.control.Attention
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.IssuerId
import civictech.cell.link.PeerId
import civictech.cell.link.PeerIdentityBinding
import civictech.cell.membrane.AuthLevel
import civictech.cell.membrane.Principal
import civictech.cell.port.PortRef
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.PeerAuthPolicy
import civictech.cell.wire.Peering
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import civictech.identity.DeterministicKeySource
import civictech.identity.PeerIdentity
import civictech.identity.anchor.AnchorIssuer
import civictech.identity.anchor.AnchorVouchedBinding
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.opentest4j.AssertionFailedError
import java.net.URI
import java.security.KeyPair
import java.util.UUID

/**
 * Feature `computenet-5y8t.5` (DSC4), task `computenet-5y8t.5.1`: the epic's
 * FIRST acceptance criterion end to end, with real crypto over a real socket —
 * a peer rotates its key, keeps its name, and an allowlist entry naming it
 * keeps admitting it with no reconfiguration. Both attribution surfaces are
 * read: the mirrored location `LocationRegistry.Remote.peer` on the listener,
 * and the `Principal.Peer` a delivery observes through `currentPrincipal()`.
 *
 * ## One fixture, two bindings (5y8t.F5-D1)
 *
 * Every method is parameterised over [Binding]. The fixture code is the same
 * for both values; the parameter supplies only which `PeerIdentityBinding`
 * the sides hold, which identities they present, and which name the listener
 * allowlists. `WsTransport`, `IrohTransport` and `BridgeCells` are the same
 * classes in both executions — that is the epic's "second binding without
 * changing call sites" criterion, on the real bindings:
 *
 * - [Binding.ANCHOR_VOUCHED]: alice is `PeerId("alice")` on K1 and again on
 *   K2, each key carrying its own statement from anchor A. The listener
 *   allowlists `PeerId("alice")`, and the rotation is ADMITTED.
 * - [Binding.INTERIM]: the pre-DSC4 behaviour, the contrast. alice's identity
 *   is unnamed, so her name is key-derived; the listener allowlists K1's
 *   key-derived name, and the rotated K2 dialer presents a DIFFERENT name and
 *   is REFUSED by the allowlist. Rotation renames under Interim.
 *
 * ## Offline (5y8t.F5-D2)
 *
 * Anchor A is an `AnchorIssuer` object in this JVM with no listener and no
 * socket. The listener's `AnchorVouchedBinding` is built from
 * `mapOf(A.issuerId to A.publicKey)` and a fixed clock, so every admission
 * below consumed only keys this side already held and bytes the dialer
 * presented in its hello. What this can NOT show is a negative about the
 * network — a test cannot prove nothing was reachable; it shows that nothing
 * the verification needed had an address to reach.
 *
 * ## What this is NOT
 *
 * No part of this class demonstrates revocation, supersession or stolen-key
 * resistance. The old-key method below pins the
 * opposite on purpose: DSC4 builds no supersession (residual R4), so a
 * dialer still holding K1 and its ORIGINAL issuance-1 statement is still
 * `alice`. `[DSC1-NV-01]` (stolen-key resistance) stays EXPLICITLY UNVERIFIED.
 *
 * ## Why each dialer is a freshly built `Side` with an explicit incarnation (5y8t.F5-D8)
 *
 * The announcement replay ledger is keyed by the bound NAME (5y8t.7-D3), and
 * a signer's counter floor is `incarnation shl 20`, read once when its `Side`
 * is built. Each dialer is therefore built only when it is about to dial,
 * with incarnations 1 (K1), 2 (K2) and 3 (K1 again), so each later dialer's
 * floor exceeds the name's recorded high water by construction rather than by
 * wall-clock order. Re-dialing the ORIGINAL K1 `Side` after K2 announced
 * would have its signed announcements refused REPLAY while its hello and
 * stamp still passed; that `Side` is never reused.
 */
class WsStableNameRotationOfflineAnchorTest {

    /** The parameter: which binding the fixture runs under. */
    enum class Binding { INTERIM, ANCHOR_VOUCHED }

    private val fixedNow = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    /** Anchor A: an object in this JVM. No listener, no socket, ever. */
    private val anchorA = AnchorIssuer(PeerIdentity(DeterministicKeySource.keyPairFromSeed("rotation-anchor-A".toByteArray())))

    private val aliceName = PeerId("alice")
    private val k1: KeyPair = DeterministicKeySource.keyPairFromSeed("rotation-alice-K1".toByteArray())
    private val k2: KeyPair = DeterministicKeySource.keyPairFromSeed("rotation-alice-K2".toByteArray())
    private val lKeys: KeyPair = DeterministicKeySource.keyPairFromSeed("rotation-listener-L".toByteArray())

    private fun keyIdOf(keys: KeyPair) = PeerIdentity(keys).keyId

    /** The binding every side holds under [b]; A's public key is the only issuer material. */
    private fun bindingFor(b: Binding): PeerIdentityBinding = when (b) {
        Binding.INTERIM -> PeerIdentityBinding.Interim
        Binding.ANCHOR_VOUCHED -> AnchorVouchedBinding(mapOf(anchorA.issuerId to anchorA.publicKey), clock = { fixedNow })
    }

    /**
     * alice's identity on [keys] under [b]. Under ANCHOR_VOUCHED a named
     * identity with A's statement at [issuance]; under INTERIM unnamed, so her
     * name is the key-derived one.
     */
    private fun aliceOn(b: Binding, keys: KeyPair, issuance: Long): PeerIdentity = when (b) {
        Binding.INTERIM -> PeerIdentity(keys)
        Binding.ANCHOR_VOUCHED ->
            PeerIdentity(keys, aliceName, listOf(anchorA.bind(aliceName, keyIdOf(keys), issuance, fixedNow - day, fixedNow + day)))
    }

    /** The listener's identity: named by A under ANCHOR_VOUCHED (so alice's anchor-bound side admits it), unnamed under INTERIM. */
    private fun listenerIdentity(b: Binding): PeerIdentity = when (b) {
        Binding.INTERIM -> PeerIdentity(lKeys)
        Binding.ANCHOR_VOUCHED -> {
            val l = PeerId("L")
            PeerIdentity(lKeys, l, listOf(anchorA.bind(l, keyIdOf(lKeys), 1, fixedNow - day, fixedNow + day)))
        }
    }

    /** The issuer a delivery from alice is stamped with. */
    private fun issuerFor(b: Binding): IssuerId? = when (b) {
        Binding.INTERIM -> null
        Binding.ANCHOR_VOUCHED -> anchorA.issuerId
    }

    /**
     * A `RequireAuthenticated` peering side that signs and verifies
     * announcements. [incarnation] is the signer's explicit incarnation
     * (5y8t.F5-D8); the listener keeps the wall-clock default, since nothing
     * here re-dials it.
     */
    private class Side(
        identity: PeerIdentity,
        binding: PeerIdentityBinding,
        allow: Set<PeerId>? = null,
        incarnation: (() -> Long)? = null,
    ) {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(
            registry,
            bridgeHost,
            allow = allow,
            auth = PeerAuthPolicy.RequireAuthenticated(),
            credentials = identity.asPeerCredentials(),
            announcementSigning =
                if (incarnation != null) socketAnnouncementSigning(incarnation = incarnation) else socketAnnouncementSigning(),
            announcementVerification = socketAnnouncementVerification(),
            identityBinding = binding,
        )
    }

    /** Listener L, its probe cell, its socket, and the allowlist it was built with. */
    private inner class Listening(val b: Binding) : AutoCloseable {
        /** The one allowlist entry: the stable name, or K1's key-derived name under INTERIM. */
        val allowed: Set<PeerId> = setOf(aliceOn(b, k1, 1).peerId)
        val l = Side(listenerIdentity(b), bindingFor(b), allow = allowed)
        val allowAtStart: Set<PeerId>? = l.side.allow
        val probe = WsPrincipalPromotionTest.PrincipalProbeCell().also { l.host.managementInlet.call.spawn(it) }
        val listener = WsTransport.listen(0, l.side)
        val uri = URI("ws://localhost:${listener.port}")
        val admission = requireNotNull(l.side.announcementAdmission)

        override fun close() {
            runCatching { listener.stop(1000) }
        }
    }

    /**
     * Builds a dialer `Side` NOW (never earlier — 5y8t.F5-D8), spawns a cell
     * on it, dials L, and asserts the admitted outcome: the dialer's cell is
     * mirrored at L as `Remote(_, expectedName)` at `Authenticated`, and one
     * `PORT_PROTOCOL` delivery from the dialer to L's probe is stamped
     * `Principal.Peer(expectedName, Authenticated, issuer)`. Also asserts no
     * denial and no rejected announcement was charged to L by this dial, and
     * that the replay ledger records the expected name.
     */
    private fun Listening.dialAndAssertAdmitted(identity: PeerIdentity, incarnation: Long) {
        val deniedBefore = listener.admissionDenialCount
        val dialer = Side(identity, bindingFor(b), incarnation = { incarnation })
        val cell = WsPrincipalPromotionTest.PrincipalProbeCell().also { dialer.host.managementInlet.call.spawn(it) }
        val stampsBefore = probe.principals.size
        val connection = WsTransport.connect(uri, dialer.side) { 0L }
        try {
            await(
                "L mirrored the dialer's cell and both ends reached Authenticated",
                detail = {
                    "L.admissionDenialCount=${listener.admissionDenialCount} (was $deniedBefore), " +
                        "L.rejectedAnnouncements=${admission.rejectedAnnouncements}, " +
                        "dialer achievedAuthLevel=${connection.achievedAuthLevel}"
                },
            ) {
                l.registry.location(cell.ref) is LocationRegistry.Remote &&
                    dialer.registry.location(probe.ref) is LocationRegistry.Remote &&
                    connection.achievedAuthLevel == AuthLevel.Authenticated
            }
            (l.registry.location(cell.ref) as LocationRegistry.Remote).peer shouldBe identity.peerId

            // Routed by the dialer's registry through the connection's egress,
            // decoded and stamped by L's BridgeIngressCell, dispatched on L.
            dialer.registry.deliver(attention(probe.ref))
            await("the delivery crossed to L's probe") { probe.principals.size > stampsBefore }
            probe.principals.last() shouldBe Principal.Peer(identity.peerId, AuthLevel.Authenticated, issuerFor(b))

            listener.admissionDenialCount shouldBe deniedBefore
            admission.rejectedAnnouncements shouldBe 0L
            admission.highWaterFor(identity.peerId) shouldNotBe null
            if (b == Binding.ANCHOR_VOUCHED) {
                // The ledger is keyed by the bound NAME (5y8t.7-D3), never by the key's fingerprint.
                admission.highWaterFor(PeerId(identity.keyId.name)).shouldBeNull()
            }
            l.side.allow shouldBeSameInstanceAs allowAtStart
            l.side.allow shouldBe allowed
        } finally {
            connection.shutdown()
        }
    }

    /**
     * The rotation, steps 1 and 2: alice on K1 dials and is admitted; alice
     * rotates to K2 (a NEW `PeerIdentity` with a NEW statement at issuance 2,
     * 5y8t.F5-D4) and dials the same listener with `allow` untouched.
     * ANCHOR_VOUCHED admits under the same name; INTERIM refuses, because K2's
     * key-derived name is not the allowlisted one.
     */
    private fun Listening.rotate() {
        keyIdOf(k1) shouldNotBe keyIdOf(k2) // so nothing below can pass by the keys being one key

        val alice1 = aliceOn(b, k1, issuance = 1)
        dialAndAssertAdmitted(alice1, incarnation = 1L)

        val alice2 = aliceOn(b, k2, issuance = 2) // built only now, after the K1 run announced
        when (b) {
            Binding.ANCHOR_VOUCHED -> {
                alice2.peerId shouldBe alice1.peerId
                alice2.peerId shouldBe aliceName
                dialAndAssertAdmitted(alice2, incarnation = 2L)
                listener.admissionDenialCount shouldBe 0L
            }
            Binding.INTERIM -> {
                alice2.peerId shouldNotBe alice1.peerId // rotation RENAMES under Interim
                dialAndAssertRefusedByAllowlist(alice2, incarnation = 2L)
            }
        }
    }

    /**
     * The INTERIM step 2: L's allowlist refuses the renamed dialer exactly
     * once, and no `Remote` for its cell ever appears at L within a bounded
     * wait. The dial's backoff is a minute, so the transport does not re-dial
     * inside the observation window and charge L a second denial.
     */
    private fun Listening.dialAndAssertRefusedByAllowlist(identity: PeerIdentity, incarnation: Long) {
        val deniedBefore = listener.admissionDenialCount
        val dialer = Side(identity, bindingFor(b), incarnation = { incarnation })
        val cell = WsPrincipalPromotionTest.PrincipalProbeCell().also { dialer.host.managementInlet.call.spawn(it) }
        val connection = WsTransport.connect(uri, dialer.side) { 60_000L }
        try {
            await("L refused the renamed dialer") { listener.admissionDenialCount > deniedBefore }
            val absenceDeadline = System.currentTimeMillis() + 1_500
            while (System.currentTimeMillis() < absenceDeadline) {
                l.registry.location(cell.ref).let { it is LocationRegistry.Remote } shouldBe false
                Thread.sleep(50)
            }
            listener.admissionDenialCount shouldBe deniedBefore + 1
            l.side.allow shouldBeSameInstanceAs allowAtStart
        } finally {
            connection.shutdown()
        }
    }

    /**
     * The epic's first criterion under ANCHOR_VOUCHED — rotation under a stable
     * name admits with `allow` unchanged, attributed to `alice` on both
     * surfaces both times — and, under INTERIM, its contrast: the same steps
     * refuse the rotated key, because under Interim a new key is a new name.
     */
    @ParameterizedTest
    @EnumSource(Binding::class)
    fun `a key rotation under a stable name keeps the allowlist admitting and attribution on the name, where Interim renames and refuses`(
        b: Binding,
    ) {
        Listening(b).use { it.rotate() }
    }

    /**
     * Residual R4, pinned deliberately (5y8t.F5-D3): after the rotation, a
     * FRESHLY built dialer still holding K1 with its ORIGINAL issuance-1
     * statement (incarnation 3) is STILL admitted, with the same stamp. This is
     * the ABSENCE of revocation and supersession in DSC4 — a later `issuance`
     * does not retire an earlier one — and NOT stolen-key resistance, which
     * this test does not and cannot show: `[DSC1-NV-01]` stays EXPLICITLY
     * UNVERIFIED. Under INTERIM the K1 name is still the allowlisted one, so it
     * is admitted there too, after the K2 refusal.
     */
    @ParameterizedTest
    @EnumSource(Binding::class)
    fun `the old key is still admitted after rotation because DSC4 builds no supersession (R4), not stolen-key resistance`(
        b: Binding,
    ) {
        Listening(b).use { at ->
            at.rotate()
            val deniedAfterRotation = at.listener.admissionDenialCount
            at.dialAndAssertAdmitted(aliceOn(b, k1, issuance = 1), incarnation = 3L)
            at.listener.admissionDenialCount shouldBe deniedAfterRotation
            deniedAfterRotation shouldBe if (b == Binding.INTERIM) 1L else 0L
        }
    }

    private fun await(what: String, timeoutMs: Long = 30_000, detail: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what ${detail()}")
            Thread.sleep(50)
        }
    }

    private fun attention(target: CellRef) = HostedPortInvocation(
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
}
