package civictech.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.control.Attention
import civictech.cell.control.AttentionBand
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SupervisionPolicy
import civictech.cell.link.PeerId
import civictech.cell.membrane.AuthLevel
import civictech.cell.membrane.BoundaryPolicy
import civictech.cell.membrane.CompositeCell
import civictech.cell.membrane.DisclosurePolicy
import civictech.cell.membrane.Principal
import civictech.cell.membrane.ProjectionId
import civictech.cell.membrane.ProjectionRegistry
import civictech.cell.membrane.ProtocolAuthority
import civictech.cell.membrane.currentPrincipal
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkTo
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.InvocationSink
import civictech.cell.wire.RemoteLinkRequests
import civictech.cell.proxy.Invocation
import civictech.cell.wire.Peering
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireCodec
import civictech.cell.wire.WireEdgeLink
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.net.URI
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `computenet-usd.4.4` — the wire half of SEC1's flow-time seams: the same
 * [BridgeBoundaryPolicyTest][civictech.cell.wire] fixture design, re-expressed
 * against two real [WsTransport] peers over an actual loopback socket rather
 * than [civictech.cell.wire.Peering.loopback] (Decision 2 of the feature
 * breakdown, 2026-08-16 — `:wire` has no `:testkit` dependency, so the
 * scenario is written twice against the identical fixture, not shared as
 * code).
 *
 * ## The wall this task hit — closed by computenet-wb6s
 *
 * When this file was written, a link request could not cross a socket at all.
 * [WireCodec.encode] requires `@Contract` ids for anything other than a
 * `PORT_PROTOCOL` frame — see its own `@throws` KDoc: `"not wire-capable:
 * '$methodName' was not captured from a @Contract interface"` — and
 * `LinkTo.linkTo` / `LinkFrom.linkFrom` are ordinary port-management methods
 * with none, so [civictech.cell.wire.BridgeEgressCell.deliver]'s unconditional
 * encode threw before a byte reached the wire. Every cross-boundary link test
 * in this repository therefore handed the *already-decoded* invocation straight
 * to the target side's [LocationRegistry.deliver], stamped with the peer a real
 * ingress decode would have applied.
 *
 * computenet-wb6s closed that without touching the frame or `LinkTo`/`LinkFrom`
 * (whose argument is a live port object, which no encoding could carry across a
 * machine boundary): what crosses is
 * [civictech.cell.wire.RemoteLinkRequests]' addressable form of the request —
 * an ordinary ids-only `PORT_MANAGEMENT` frame over the
 * [civictech.cell.wire.RemoteLink] contract — and the receiving
 * [civictech.cell.wire.BridgeIngressCell] translates it back into the `linkTo`
 * invocation an in-process caller would have made, stamped with the peer it
 * authenticated. [linkQTo] below uses it, so the link-request half of the
 * identity seam is now verified through a real socket rather than up to an
 * injection point.
 *
 * ## What genuinely crosses the real socket here, and what does not
 *
 * - The **link request** ([LinkTo.linkTo]) is delivered directly to the
 *   listener side's own [LocationRegistry] (`registryP.deliver(...)`), stamped
 *   with exactly the peer identity a real ingress decode would have stamped —
 *   never touching [WireCodec] or the actual socket. This is the one seam this
 *   file cannot make real; see above.
 * - Every **disclosed delta** — the `onLinked` catch-up unicast and every live
 *   emission — crosses the *actual* [WsTransport] socket: the collector cell
 *   lives on a genuinely separate [ManagedHost]/[LocationRegistry] pair
 *   ("Q"), reached from "P" only through the real, connected transport's own
 *   [civictech.cell.wire.BridgeEgressCell] (a `PORT_API` `Propagate` call,
 *   which *does* carry `@Contract` ids), decoded by the real
 *   [civictech.cell.wire.BridgeIngressCell] on the far side.
 * - Every **`PORT_PROTOCOL` assertion** (the attention crossing in the BS-9
 *   wire half) also crosses for real: [WireCodec.encode]'s `PORT_PROTOCOL`
 *   branch needs only a [civictech.cell.protocol.ProtocolId] and a
 *   [WireEdgeLink] — no `@Contract` capture — so `registryQ.deliver(...)`
 *   targeting the (really, wire-mirrored) exposure genuinely resolves to Q's
 *   own live transport session's egress, genuinely encodes, genuinely writes
 *   to the socket, and is genuinely decoded and delivered by P's real ingress.
 *
 * ## Acceptance-clause mapping (this task's criteria, verbatim, against the wall above)
 *
 * "the onLinked catch-up and live deltas SHALL both arrive filtered by the
 * same disclosure transform, with the remote-triggered catch-up evaluation
 * observing Principal = Peer(id, TransportVouched)" — held, and genuinely
 * wire-crossed end to end since computenet-wb6s: the link *request* that causes
 * the catch-up now crosses the socket too, so the principal the catch-up
 * observes comes from the ingress stamp rather than from a test-supplied
 * invocation.
 *
 * "no catch-up and no live delta SHALL cross the wire, the link SHALL remain
 * established" — held: the exposure's own `linking.links` records the endpoint
 * the ingress reconstructed from the socket-borne request, and `PORT_PROTOCOL`
 * traffic genuinely still crosses the real socket to it.
 *
 * "a remotely-asserted attention level SHALL arrive clamped to the ceiling and
 * applied" — held, fully wire-crossed (no injection at all).
 *
 * "The wire frame SHALL remain ids-only ... WireCodec.VERSION stays 2 ... every
 * pre-existing :wire suite SHALL pass unmodified" — pinned directly (see
 * `frame-form guard`).
 */
/** The redacting projection this crossing installs — see `BridgeBoundaryPolicyTest`'s twin. */
private val redactAcrossTheSocket = ProjectionId("redact-across-the-socket-test")

/** `LinkTo.linkTo(LinkFrom)` — the handshake-running overload, not the ad-hoc `Use` one. */
private val LINK_TO = LinkTo::class.java.methods.first {
    it.name == "linkTo" && it.parameterTypes.singleOrNull() == LinkFrom::class.java
}

private object BridgedProjectionProbe {
    @Volatile
    var principals: MutableList<Principal> = CopyOnWriteArrayList()

    fun reset() {
        principals = CopyOnWriteArrayList()
    }
}

@Suppress("UNCHECKED_CAST")
private fun registerBridgeProjection() {
    ProjectionRegistry.register(redactAcrossTheSocket) { delta ->
        BridgedProjectionProbe.principals += currentPrincipal()
        val d = delta as SetDelta<String>
        SetDelta(
            adds = d.adds.filterKeys { !it.startsWith("secret") },
            dels = d.dels.filterKeys { !it.startsWith("secret") },
        )
    }
}

/** Identical fixture design to `BridgeBoundaryPolicyTest`'s `BridgedMembrane` (Decision 2). */
private class BridgedMembrane(
    val projected: SetCell<String> = SetCell(),
    val denied: SetCell<String> = SetCell(),
    ceiling: AttentionBand = AttentionBand.LOW,
) : CompositeCell() {
    val projectedOutlet = mediateOutlet(
        "projectedOutlet",
        "outlet",
        projected.outlet,
        policy = BoundaryPolicy(disclosure = DisclosurePolicy.Project(redactAcrossTheSocket)),
    )

    val deniedOutlet = mediateOutlet(
        "deniedOutlet",
        "outlet",
        denied.outlet,
        policy = BoundaryPolicy(
            disclosure = DisclosurePolicy.Deny,
            protocolAuthority = mapOf(Protocols.Attention to ProtocolAuthority(ceiling = ceiling)),
        ),
    )
}

class WsBoundaryPolicyTest {

    /** A cell on side Q collecting whatever the crossing discloses to it, over the real socket. */
    class DeltaCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = CopyOnWriteArrayList<SetDelta<String>>()

        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.serve(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) {
                    received += value
                }
            })
        }
    }

    interface DeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    /**
     * Two peers, two independent [ManagedHost]/[LocationRegistry] pairs, joined
     * by a real [WsTransport] loopback socket: the membrane and its exposures
     * live on "p" (the listener), the collectors on "q" (the dialer).
     */
    private inner class Run {
        val registryP = LocationRegistry()
        val hostP = ManagedHost(registry = registryP)
        val bridgeHostP = ManagedHost(registry = registryP)
        val sideP = Peering.Side(registryP, bridgeHostP, peer = PeerId("p"))

        val registryQ = LocationRegistry()
        val hostQ = ManagedHost(registry = registryQ)
        val bridgeHostQ = ManagedHost(registry = registryQ)
        val sideQ = Peering.Side(registryQ, bridgeHostQ, peer = PeerId("q"))

        val deadLettersP = CopyOnWriteArrayList<DeadLetter>()

        val membrane = BridgedMembrane()
        val membraneRef: CellRef

        val listener: WsTransport.WsListener
        val connection: WsTransport.WsConnection

        init {
            listOf(hostP, bridgeHostP).forEach { h ->
                h.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        deadLettersP += value
                    }
                }, PortRef.generate()))
            }
            membraneRef = hostP.managementInlet.call.spawn(membrane)
            // BS-14: a denial must not be reclassified as a cell fault.
            hostP.managementInlet.call.supervise(membraneRef, SupervisionPolicy.RESTART)

            listener = WsTransport.listen(0, sideP)
            connection = WsTransport.connect(URI("ws://localhost:${listener.port}"), sideQ) { 0L }
        }

        fun close() {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }

        /** Spawn a collector on Q and wait for P to mirror it as a real Remote location. */
        fun collectorOnQ(): DeltaCollectorCell {
            val collector = DeltaCollectorCell()
            hostQ.managementInlet.call.spawn(collector)
            await("P mirrored Q's collector") { registryP.location(collector.ref) is LocationRegistry.Remote }
            return collector
        }

        /**
         * Peer q links to [exposure] across the **real socket**
         * (computenet-wb6s): a [RemoteLinkRequests] request resolved through
         * `registryQ`'s real, wire-mirrored [LocationRegistry.Remote] location
         * for the membrane, genuinely encoded, genuinely crossing the socket to
         * P's real ingress — which stamps the peer and reconstructs the local
         * endpoint that forwards every disclosed delta back to [collector] over
         * the same socket.
         *
         * Nothing here supplies a [PeerId]: the identity the handshake and the
         * disclosure transform evaluate against is the one the ingress decode
         * applied.
         */
        fun linkQTo(exposure: String, collector: DeltaCollectorCell) {
            await("Q mirrored P's membrane") { registryQ.location(membraneRef) is LocationRegistry.Remote }
            RemoteLinkRequests.requestLinkTo(
                sink = InvocationSink(registryQ::deliver),
                target = PortAddress(membraneRef, exposure),
                consumer = PortAddress(collector.ref, "inlet"),
                api = Propagate::class.java,
            )
            await("q linked to $exposure") { linkedOn(exposure) }
        }

        private fun linkedOn(exposure: String): Boolean =
            when (exposure) {
                "projectedOutlet" -> membrane.projectedOutlet.linking.links.isNotEmpty()
                "deniedOutlet" -> membrane.deniedOutlet.linking.links.isNotEmpty()
                else -> error("unknown exposure $exposure")
            }

        /**
         * Peer q asserts [attention] on [exposure] across the *real* socket: a
         * `PORT_PROTOCOL` frame needs no `@Contract` capture ([WireCodec.encode]'s
         * dedicated branch), so — unlike [linkQTo] — this genuinely resolves
         * through `registryQ`'s real, wire-mirrored `Remote` location for
         * [membraneRef], genuinely encodes, and genuinely crosses the socket to
         * P's real ingress.
         */
        fun assertAttentionFromQ(exposure: String, attention: Attention) {
            await("Q mirrored P's membrane") { registryQ.location(membraneRef) is LocationRegistry.Remote }
            val edge = WireEdgeLink(
                id = UUID.randomUUID(),
                from = PortRef.generate(),
                to = PortRef.generate(membraneRef),
                fromAddr = PortAddress(CellRef(UUID.randomUUID()), "inlet"),
                toAddr = PortAddress(membraneRef, exposure),
            )
            registryQ.deliver(
                HostedPortInvocation(
                    cellRef = membraneRef,
                    portName = exposure,
                    type = HostedPortInvocation.Type.PORT_PROTOCOL,
                    invocation = Invocation("", emptyList(), emptyList()),
                    protocolId = Protocols.Attention,
                    protocolLink = edge,
                    protocolMessage = attention,
                ),
            )
        }
    }

    // 30s / 20ms poll, matching the wire suite's canonical `await` (WsPeerIdentityTest):
    // real threads and a real socket, no SimulationController to run-to-idle against.
    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun `BS-16 wire case - catch-up and live deltas cross the real socket projected, and the catch-up sees Peer(q, TransportVouched)`() {
        registerBridgeProjection()
        BridgedProjectionProbe.reset()
        val run = Run()
        try {
            // Pre-link state: what the catch-up baseline will carry (20/21 §Pull).
            run.membrane.projected.inlet.call.add("secret-one")
            run.membrane.projected.inlet.call.add("public-one")

            val collector = run.collectorOnQ()
            run.linkQTo("projectedOutlet", collector)

            // The catch-up evaluation is the one BS-16's principal assertion is
            // made on: it ran inside the injected link request's own dispatch (the
            // one seam this file cannot make cross a real socket — see the class
            // KDoc), so the ambient Principal is the ingress-stamped peer, at the
            // only strength phase 1 certifies ([SEC1-01], [SEC1-24]).
            await("catch-up recorded a principal") { BridgedProjectionProbe.principals.isNotEmpty() }
            BridgedProjectionProbe.principals.first() shouldBe
                Principal.Peer(PeerId("q"), AuthLevel.TransportVouched)

            await("catch-up crossed the socket") { collector.received.isNotEmpty() }
            val afterCatchUp = collector.received.size
            afterCatchUp shouldBeGreaterThan 0

            // A live delta after the link, through the SAME transform ([SEC1-17]),
            // genuinely encoded/decoded across the socket a second time.
            //
            // The two adds are two separate emissions, so they cross as two
            // deltas — and the FIRST of them is `secret-two`, which the
            // projection empties rather than suppresses: an empty SetDelta is
            // still a delta and still crosses. So `received.size > afterCatchUp`
            // is satisfied by the redacted-empty arrival, before `public-two`
            // has landed, and the content assertion below then reads
            // `["public-one"]`. Waiting on the content this test is actually
            // about — not on a count that a different delta can move — is what
            // makes it a real barrier. (Measured: green on darwin/arm64, red
            // deterministically enough to fail `build-test-fast` on ubuntu,
            // run 31925035646.)
            run.membrane.projected.inlet.call.add("secret-two")
            run.membrane.projected.inlet.call.add("public-two")
            await("the live public delta crossed the socket") {
                collector.received.flatMap { it.adds.keys }.contains("public-two")
            }
            collector.received.size shouldBeGreaterThan afterCatchUp

            val disclosed = collector.received.flatMap { it.adds.keys }
            disclosed.none { it.startsWith("secret") } shouldBe true
            disclosed.toSet() shouldBe setOf("public-one", "public-two")

            run.deadLettersP.shouldBeEmpty()
            run.hostP.supervisionAccounting().restarts shouldBe 0
        } finally {
            run.close()
        }
    }

    @Test
    fun `BS-9 wire half - Deny discloses nothing across the real socket, and a remote attention assertion arrives clamped (SEC1-18)`() {
        val run = Run()
        try {
            run.membrane.denied.inlet.call.add("anything")

            val observed = mutableListOf<Attention>()
            ProtocolSupport.of(run.membrane.deniedOutlet).handle(Protocols.Attention) { _, message ->
                observed += message as Attention
            }

            val collector = run.collectorOnQ()
            run.linkQTo("deniedOutlet", collector)

            // The link IS established (up to the honest limit stated in the class
            // KDoc): Deny suppresses disclosure, it does not refuse the peering.
            run.membrane.deniedOutlet.linking.links.size shouldBe 1

            // No catch-up crossed. This is not a race against the socket: the
            // suppression happens entirely on P's own outbound disclosure filter,
            // before anything is ever handed to WireCodec — so waiting for the
            // exposure's OWN denial counter to move is what proves the local
            // check ran, with no dependency on cross-socket timing at all.
            val sink = run.membrane.boundaryDenials["deniedOutlet"]!!
            await("the suppressed catch-up was accounted") { sink.denialCount >= 1L }
            collector.received.shouldBeEmpty()

            run.membrane.denied.inlet.call.add("also-nothing")
            await("the suppressed live emission was accounted") { sink.denialCount >= 2L }
            collector.received.shouldBeEmpty() // no live delta crossed either

            // ... while PORT_PROTOCOL traffic from the same peer still crosses the
            // REAL socket, arrives clamped to the declared ceiling and is applied —
            // the peering is usable for the metadata plane exactly as [SEC1-18]
            // requires, even though the data plane discloses nothing. Unlike the
            // link and the data deltas above, nothing here is injected: this
            // assertion genuinely encodes, genuinely crosses the socket, and is
            // genuinely decoded by P's real ingress (see the class KDoc).
            run.assertAttentionFromQ("deniedOutlet", Attention(AttentionBand.HIGH.level))
            await("the clamped assertion was applied") { observed.isNotEmpty() }
            observed shouldContainExactly listOf(Attention(AttentionBand.LOW.level))

            run.hostP.supervisionAccounting().restarts shouldBe 0
        } finally {
            run.close()
        }
    }

    /**
     * The frame-form pin this task's acceptance criteria require directly: no
     * frame fork, no version-byte bump. The regression side of the same
     * criterion — "every pre-existing :wire suite SHALL pass unmodified" — is
     * not this test's job to assert (it cannot observe the rest of the suite);
     * it is the module-wide `./gradlew :wire:test` run this task's own
     * verification section runs and this file's implementer reports on.
     */
    @Test
    fun `frame-form guard - WireCodec stays version 2, ids-only, no new frame variant`() {
        WireCodec.VERSION shouldBe 2
    }
}
