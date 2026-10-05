package civictech.wire

import civictech.cell.BoundarySeam
import civictech.cell.CellRef
import civictech.cell.ClaimClass
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.control.Attention
import civictech.cell.control.AttentionBand
import civictech.cell.data.SetCell
import civictech.cell.host.DeadLetter
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.link.PeerIdentityBinding
import civictech.cell.membrane.AuthLevel
import civictech.cell.membrane.BoundaryPolicy
import civictech.cell.membrane.CompositeCell
import civictech.cell.membrane.ProtocolAuthority
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.PeerAuthPolicy
import civictech.cell.wire.Peering
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireCodec
import civictech.cell.wire.WireEdgeLink
import civictech.economy.EconomicPolicy
import civictech.economy.LedgerSnapshot
import civictech.economy.TokenBucketLedger
import civictech.identity.DeterministicKeySource
import civictech.identity.PeerIdentity
import civictech.identity.anchor.AnchorIssuer
import civictech.identity.anchor.AnchorVouchedBinding
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.opentest4j.AssertionFailedError
import java.net.URI
import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private class ReconnectBudgetMembrane : CompositeCell() {
    private val source = SetCell<String>()

    val exposure = mediateOutlet(
        "attention",
        "outlet",
        source.outlet,
        policy = BoundaryPolicy(
            protocolAuthority = mapOf(
                Protocols.Attention to ProtocolAuthority(ceiling = AttentionBand.LOW),
            ),
        ),
    )
}

/**
 * BS-16 / `[ECO1-PAR-09r]`: a depleted Attention bucket belongs to the
 * listener-visible [PeerId], not to one WebSocket connection or one key.
 *
 * A real socket drop is simulated exactly as the DSC4 rotation suite does it:
 * [WsTransport.WsConnection.shutdown] followed by a fresh dial from a freshly
 * built [Peering.Side]. The dialers use monotonically increasing announcement
 * incarnations 1, 2 and 3; no `Side` is reused across a reconnect.
 *
 * Under [Binding.ANCHOR_VOUCHED], K1 and K2 both resolve to the stable name
 * `alice`, so same-key reconnect and key rotation resume the one depleted
 * bucket. Under [Binding.INTERIM], K2 derives a different name and therefore
 * receives its own bootstrap. That contrast is intentional: rotation renames
 * under Interim, and the budget is keyed on the name DSC4 made stable.
 */
class WsBudgetReconnectResumesTest {

    enum class Binding { INTERIM, ANCHOR_VOUCHED }

    private val fixedNow = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val anchor = AnchorIssuer(
        PeerIdentity(DeterministicKeySource.keyPairFromSeed("budget-reconnect-anchor".toByteArray())),
    )
    private val aliceName = PeerId("alice")
    private val k1: KeyPair = DeterministicKeySource.keyPairFromSeed("budget-reconnect-alice-K1".toByteArray())
    private val k2: KeyPair = DeterministicKeySource.keyPairFromSeed("budget-reconnect-alice-K2".toByteArray())
    private val listenerKeys: KeyPair =
        DeterministicKeySource.keyPairFromSeed("budget-reconnect-listener".toByteArray())

    private fun keyIdOf(keys: KeyPair) = PeerIdentity(keys).keyId

    private fun bindingFor(binding: Binding): PeerIdentityBinding = when (binding) {
        Binding.INTERIM -> PeerIdentityBinding.Interim
        Binding.ANCHOR_VOUCHED ->
            AnchorVouchedBinding(mapOf(anchor.issuerId to anchor.publicKey), clock = { fixedNow })
    }

    private fun aliceOn(binding: Binding, keys: KeyPair, issuance: Long): PeerIdentity = when (binding) {
        Binding.INTERIM -> PeerIdentity(keys)
        Binding.ANCHOR_VOUCHED -> namedIdentity(aliceName, keys, issuance)
    }

    private fun listenerIdentity(binding: Binding): PeerIdentity = when (binding) {
        Binding.INTERIM -> PeerIdentity(listenerKeys)
        Binding.ANCHOR_VOUCHED -> namedIdentity(PeerId("L"), listenerKeys, issuance = 1)
    }

    private fun namedIdentity(name: PeerId, keys: KeyPair, issuance: Long): PeerIdentity =
        PeerIdentity(
            keys,
            name,
            listOf(anchor.bind(name, keyIdOf(keys), issuance, fixedNow - day, fixedNow + day)),
        )

    private fun policy(): EconomicPolicy = EconomicPolicy.placeholder().copy(
        prices = mapOf(ClaimClass.Attention to 1L),
        capacities = mapOf(ClaimClass.Attention to 10L),
        refill = mapOf(ClaimClass.Attention to EconomicPolicy.Refill(1, 1_000_000_000)),
        issuers = mapOf(
            anchor.issuerId.name to EconomicPolicy.IssuerBudget(
                bootstrap = mapOf(ClaimClass.Attention to 3L),
            ),
        ),
        unvouchedBootstrap = mapOf(ClaimClass.Attention to 3L),
    )

    private class Side(
        identity: PeerIdentity,
        binding: PeerIdentityBinding,
        budget: TokenBucketLedger? = null,
        incarnation: (() -> Long)? = null,
    ) {
        val registry = LocationRegistry()
        val host =
            if (budget == null) {
                ManagedHost(registry = registry)
            } else {
                ManagedHost(registry = registry, budget = budget)
            }
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(
            registry,
            bridgeHost,
            auth = PeerAuthPolicy.RequireAuthenticated(),
            credentials = identity.asPeerCredentials(),
            announcementSigning =
                if (incarnation == null) {
                    socketAnnouncementSigning()
                } else {
                    socketAnnouncementSigning(incarnation = incarnation)
                },
            announcementVerification = socketAnnouncementVerification(),
            identityBinding = binding,
        )
    }

    private inner class Listening(val binding: Binding) : AutoCloseable {
        val ledger = TokenBucketLedger(policy().applied(), { 0L }, "L")
        val listenerSide = Side(listenerIdentity(binding), bindingFor(binding), budget = ledger)
        val deadLetters = CopyOnWriteArrayList<DeadLetter>()
        val membrane = ReconnectBudgetMembrane()
        val membraneRef: CellRef
        val observed = CopyOnWriteArrayList<Attention>()
        val listener: WsTransport.WsListener
        val uri: URI

        init {
            listenerSide.host.deadLetterOutlet.subscribe(
                Use.fixed(
                    object : Propagate<DeadLetter> {
                        override fun propagate(value: DeadLetter) {
                            deadLetters += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
            membraneRef = listenerSide.host.managementInlet.call.spawn(membrane)
            ProtocolSupport.of(membrane.exposure).handle(Protocols.Attention) { _, message ->
                observed += message as Attention
            }
            listener = WsTransport.listen(0, listenerSide.side)
            uri = URI("ws://localhost:${listener.port}")
        }

        fun dial(identity: PeerIdentity, incarnation: Long): Connected =
            Connected(
                Side(identity, bindingFor(binding), incarnation = { incarnation }),
                identity.peerId,
            )

        inner class Connected(
            private val dialer: Side,
            private val peer: PeerId,
        ) : AutoCloseable {
            private val connection = WsTransport.connect(uri, dialer.side) { 0L }

            init {
                await("$peer authenticated and mirrored the listener membrane") {
                    connection.achievedAuthLevel == AuthLevel.Authenticated &&
                        dialer.registry.location(membraneRef) is LocationRegistry.Remote
                }
            }

            fun send(version: Long) {
                dialer.registry.deliver(attention(membraneRef, version))
            }

            override fun close() {
                connection.shutdown()
            }
        }

        override fun close() {
            runCatching { listener.stop(1000) }
        }
    }

    @ParameterizedTest
    @EnumSource(Binding::class)
    fun `a reconnect resumes the depleted stable-name budget, while Interim key rotation creates a new name`(
        binding: Binding,
    ) {
        WireCodec.VERSION shouldBe 2
        val alice1 = aliceOn(binding, k1, issuance = 1)
        var version = 0L

        Listening(binding).use { at ->
            // a. Spend the three-token bootstrap, then refuse the fourth frame.
            at.dial(alice1, incarnation = 1).use { first ->
                repeat(3) { first.send(++version) }
                await("three Attention assertions were applied") { at.observed.size == 3 }
                first.send(++version)
                awaitDenial(at, count = 1L, peer = alice1.peerId)
                awaitSnapshot(at, "three admissions depleted alice's only bucket") { snapshot ->
                    snapshot.bucketCount == 1 &&
                        snapshot.bucket(alice1.peerId, ClaimClass.Attention)?.balance == 0L &&
                        snapshot.admitted[ClaimClass.Attention] == 3L
                }
                at.observed.map { it.version } shouldContainExactly listOf(1L, 2L, 3L)
                at.listener.admissionDenialCount shouldBe 0L
            }

            // b. A fresh Side on the same K1/name gets no second bootstrap.
            at.dial(alice1, incarnation = 2).use { sameKeyReconnect ->
                sameKeyReconnect.send(++version)
                awaitDenial(at, count = 2L, peer = alice1.peerId)
                awaitSnapshot(at, "the same-key reconnect kept exactly one depleted bucket") { snapshot ->
                    snapshot.bucketCount == 1 &&
                        snapshot.bucket(alice1.peerId, ClaimClass.Attention)?.balance == 0L &&
                        snapshot.admitted[ClaimClass.Attention] == 3L &&
                        snapshot.deniedCount() == 2L
                }
                at.observed.size shouldBe 3
                at.listener.admissionDenialCount shouldBe 0L
            }

            // c. K2 either keeps the anchor-vouched name or creates a new Interim name.
            val alice2 = aliceOn(binding, k2, issuance = 2)
            at.dial(alice2, incarnation = 3).use { rotatedKey ->
                rotatedKey.send(++version)
                when (binding) {
                    Binding.ANCHOR_VOUCHED -> {
                        alice2.peerId shouldBe alice1.peerId
                        awaitDenial(at, count = 3L, peer = alice2.peerId)
                        awaitSnapshot(at, "the rotated key resumed the same depleted stable-name bucket") { snapshot ->
                            snapshot.bucketCount == 1 &&
                                snapshot.bucket(aliceName, ClaimClass.Attention)?.balance == 0L &&
                                snapshot.admitted[ClaimClass.Attention] == 3L &&
                                snapshot.deniedCount() == 3L
                        }
                        at.observed.size shouldBe 3
                    }

                    Binding.INTERIM -> {
                        alice2.peerId shouldNotBe alice1.peerId
                        await("the renamed Interim peer received and spent one token from its own bootstrap") {
                            at.observed.size == 4 && at.ledger.snapshot().let { snapshot ->
                                snapshot.bucketCount == 2 &&
                                    snapshot.bucket(alice2.peerId, ClaimClass.Attention)?.balance == 2L &&
                                    snapshot.bucket(alice1.peerId, ClaimClass.Attention)?.balance == 0L &&
                                    snapshot.admitted[ClaimClass.Attention] == 4L
                            }
                        }
                        at.membrane.boundaryDenials["attention"]!!.denialCount shouldBe 2L
                        at.ledger.snapshot().deniedCount() shouldBe 2L
                        at.observed.map { it.version } shouldContainExactly listOf(1L, 2L, 3L, 6L)
                    }
                }
                at.listener.admissionDenialCount shouldBe 0L
            }
            version shouldBe 6L
        }
    }

    private fun awaitDenial(at: Listening, count: Long, peer: PeerId) {
        await("budget denial $count was accounted at PROTOCOL_AUTHORITY for $peer") {
            at.membrane.boundaryDenials["attention"]!!.denialCount == count &&
                at.deadLetters.size.toLong() == count &&
                at.ledger.snapshot().deniedCount() == count
        }
        val denial = at.deadLetters.last().denial!!
        denial.seam shouldBe BoundarySeam.PROTOCOL_AUTHORITY
        denial.reason shouldBe DenialReason.BUDGET_EXHAUSTED
        denial.principal shouldBe peer
        denial.subject shouldBe Protocols.Attention.name
    }

    private fun awaitSnapshot(at: Listening, what: String, condition: (LedgerSnapshot) -> Boolean) {
        await(what) { condition(at.ledger.snapshot()) }
    }

    private fun LedgerSnapshot.deniedCount(): Long =
        denied[ClaimClass.Attention]?.get(DenialReason.BUDGET_EXHAUSTED) ?: 0L

    private fun attention(target: CellRef, version: Long) = HostedPortInvocation(
        cellRef = target,
        portName = "attention",
        type = HostedPortInvocation.Type.PORT_PROTOCOL,
        invocation = Invocation("", emptyList(), emptyList()),
        protocolId = Protocols.Attention,
        protocolLink = WireEdgeLink(
            id = UUID.randomUUID(),
            from = PortRef.generate(),
            to = PortRef.generate(target),
            fromAddr = PortAddress(CellRef(UUID.randomUUID()), "inlet"),
            toAddr = PortAddress(target, "attention"),
        ),
        protocolMessage = Attention(AttentionBand.HIGH.level, version),
    )

    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
            Thread.sleep(50)
        }
    }
}
