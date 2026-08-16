package civictech.cell.wire

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
import civictech.cell.host.SimulationController
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
import civictech.cell.proxy.Invocation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The redacting projection scenario 1 installs on the crossing exposure. It
 * records the ambient [Principal] at *apply* time — the single observation
 * BS-16's principal assertion is made on — and drops every element whose key
 * starts with `secret`.
 *
 * Registered process-wide (P9: a [ProjectionId] resolves through the registry,
 * never a lambda on the wire), so the recording buffer is swapped per test
 * through [BridgedProjectionProbe] rather than captured.
 */
private val redactAcrossTheBridge = ProjectionId("redact-across-the-bridge-test")

private object BridgedProjectionProbe {
    @Volatile
    var principals: MutableList<Principal> = CopyOnWriteArrayList()

    fun reset() {
        principals = CopyOnWriteArrayList()
    }
}

@Suppress("UNCHECKED_CAST")
private fun registerBridgeProjection() {
    ProjectionRegistry.register(redactAcrossTheBridge) { delta ->
        BridgedProjectionProbe.principals += currentPrincipal()
        val d = delta as SetDelta<String>
        SetDelta(
            adds = d.adds.filterKeys { !it.startsWith("secret") },
            dels = d.dels.filterKeys { !it.startsWith("secret") },
        )
    }
}

/**
 * The membrane on side P that a remote peer traverses (spec 40/43 "the three
 * seams", decided 93 I-28 §4.1): two organelle [SetCell]s, exposed under two
 * different [BoundaryPolicy]s —
 *
 * - [projectedOutlet]: `disclosure = Project(...)`, the BS-16 crossing;
 * - [deniedOutlet]: `disclosure = Deny` plus an attention ceiling, the BS-9
 *   wire half — a peering that discloses nothing yet stays usable for
 *   `PORT_PROTOCOL`/`PORT_MANAGEMENT` traffic ([SEC1-18]).
 *
 * Property names equal the exposure names (G-17): KSP scans the property, the
 * registry indexes the string.
 */
private class BridgedMembrane(
    val projected: SetCell<String> = SetCell(),
    val denied: SetCell<String> = SetCell(),
    ceiling: AttentionBand = AttentionBand.LOW,
) : CompositeCell() {
    val projectedOutlet = mediateOutlet(
        "projectedOutlet",
        "outlet",
        projected.outlet,
        policy = BoundaryPolicy(disclosure = DisclosurePolicy.Project(redactAcrossTheBridge)),
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

/**
 * `computenet-usd.4.3` — the flow-time half of SEC1 over the deterministic
 * bridge: a **remote** `Principal` crossing a mediated `CompositeCell`
 * exposure between two [Peering.Side]s joined by [Peering.loopback], under a
 * [SimulationController] (P1: the whole wire format, no network).
 *
 * ## What rides where (Decision 1 of the feature breakdown, 2026-08-16)
 *
 * The `Exposure -> BoundaryPolicy` binding rides **nothing new**: it stays at
 * the membrane's exposed port, where `CompositeCell.mediateOutlet` installs it
 * (`FanOutlet.disclosureFilter`, `ProtocolSupport.inboundFilter`,
 * `linkAuthority`). The bridge contributes only *identity* — `BridgeIngressCell`
 * stamps the authenticated [PeerId] onto every delivery it decodes, `ManagedHost`
 * runs the dispatch under `CurrentPeer.with(peer)`, and `currentPrincipal()`
 * reads it back as `Peer(id, TransportVouched)`. Neither [Peering.Side] nor the
 * bridge cells gain a policy field, and no frame or version byte changes.
 *
 * ## Where a `Peer` principal is genuinely observable — stated honestly
 *
 * `TransportVouched` is the only strength phase 1 can certify ([SEC1-24]): the
 * transport connection vouches for the name, nothing here is forgery
 * resistance. And the ambient peer is observable at **remote-triggered**
 * evaluations only. The `onLinked` catch-up unicast runs *inside* the remote
 * link request's own dispatch, so it evaluates at `Peer(q, TransportVouched)`;
 * a later **live** broadcast is emitted by the producing cell in its own
 * dispatch, where no peer is in scope, and so evaluates at
 * [Principal.LocalTrusted] — permitted by 93 I-28 §4.2's decided fast path and
 * recorded at `CompositeCell`'s `denyDisclosure`. BS-16's principal assertion
 * is therefore made on the catch-up evaluation; what the live delta asserts is
 * that it is *projected by the same transform* ([SEC1-17]).
 *
 * That reading of "live broadcast" holds for this fixture (plain [SetCell]
 * organelles, whose emissions are driven by `PORT_API` arrivals) and for the
 * ordinary data path generally. It was **not** unconditional when this fixture
 * was written: a broadcast a remote `PORT_PROTOCOL` frame synchronously
 * *unblocks* — a `Protocols.Progress` ack completing a wave — runs inside the
 * frame `ManagedHost` installs, and therefore under the acking peer. That case
 * is now decided as `LocalTrusted` and pinned by
 * `civictech.cell.membrane.PeerUnblockedFanOutPrincipalTest`
 * (`computenet-usd.8`), which resets the peer scope at the fan-out and leaves
 * the catch-up unicast asserted below untouched. So "a live broadcast evaluates
 * at [Principal.LocalTrusted]" now holds by construction rather than by fixture.
 *
 * **`PORT_PROTOCOL` frames did not see a `Peer` principal until this task**,
 * even though the ingress stamps one on them: `ManagedHost` installed
 * `CurrentPeer.with(...)` on its `PORT_MANAGEMENT` branch alone, so an attention
 * assertion arriving over the bridge evaluated the `protocolAuthority` seam at
 * `LocalTrusted`, took the no-op fast path, and was applied **unclamped** — the
 * measurement that first ran here. The `PORT_PROTOCOL` branch now installs the
 * stamped peer too; what keeps that from over-reaching is that the stamp is
 * non-null iff a bridge ingress decoded the frame, so an in-process assertion
 * still evaluates at `LocalTrusted`. Both halves are checked together by
 * `the ceiling clamps a bridge-arriving assertion and stays a no-op for a
 * local one`.
 *
 * ## How the remote link request is delivered
 *
 * A link request is a `PORT_MANAGEMENT` invocation, and management invocations
 * are **not wire-encodable**: `WireCodec.encode` requires the `@Contract` ids
 * that only a `@Contract`-captured method carries, and `LinkTo.linkTo` /
 * `LinkFrom.linkFrom` are ordinary port-management methods with none. So the
 * request is handed to *exactly* the sink [Peering.hostIngress] gives
 * [BridgeIngressCell] — `Peering.Side.registry::deliver` — carrying *exactly*
 * the stamp that ingress applies (`decoded.copy(peer = peer)`). That is the
 * same shape `TrustBoundaryTest` uses for its link-request seam, and the same
 * `ManagedHost` `PORT_MANAGEMENT` branch consumes it.
 *
 * Everything the crossing then *emits* travels the real loopback: the catch-up
 * unicast and every live delta are encoded by [BridgeEgressCell], decoded by
 * [BridgeIngressCell] on side Q and delivered to a cell hosted there, so what
 * the assertions read at Q came off the wire.
 */
class BridgeBoundaryPolicyTest {

    /** A cell on side Q collecting whatever the crossing discloses to it. */
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
     * Two peers over [Peering.loopback]: the membrane and its exposures live on
     * P, the collectors on Q, and P's registry reaches Q's cells only through
     * the announcement mirror the peering installs.
     */
    private class Run(seed: Long = 0) {
        val controller = SimulationController(seed)

        val registryP = LocationRegistry()
        val hostP = ManagedHost(scheduler = controller.scheduler(), registry = registryP)
        val bridgeP = ManagedHost(scheduler = controller.scheduler(), registry = registryP)
        val registryQ = LocationRegistry()
        val hostQ = ManagedHost(scheduler = controller.scheduler(), registry = registryQ)
        val bridgeQ = ManagedHost(scheduler = controller.scheduler(), registry = registryQ)

        val sideP = Peering.Side(registryP, bridgeP, peer = PeerId("p"))
        val sideQ = Peering.Side(registryQ, bridgeQ, peer = PeerId("q"))
        val loopback = Peering.loopback(sideP, sideQ)

        val deadLetters = CopyOnWriteArrayList<DeadLetter>()

        val membrane = BridgedMembrane()
        val membraneRef: CellRef

        init {
            listOf(hostP, bridgeP, hostQ, bridgeQ).forEach { h ->
                h.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        deadLetters += value
                    }
                }, PortRef.generate()))
            }
            membraneRef = hostP.managementInlet.call.spawn(membrane)
            // BS-14: a denial must not be reclassified as a cell fault, so the
            // membrane is supervised RESTART throughout — a restart would be
            // observable in supervisionAccounting().
            hostP.managementInlet.call.supervise(membraneRef, SupervisionPolicy.RESTART)
            controller.runToIdle()
        }

        /** Spawn a collector on side Q and let its publication reach P's registry. */
        fun collectorOnQ(): DeltaCollectorCell {
            val collector = DeltaCollectorCell()
            hostQ.managementInlet.call.spawn(collector)
            controller.runToIdle()
            return collector
        }

        /**
         * Peer q links to [exposure] across the bridge: the local endpoint for
         * the remote consumer forwards every disclosed delta onto the wire
         * (`registryP` resolves [collector] as a `Remote` routed through the
         * peering's egress), and the link request itself arrives peer-stamped
         * through the ingress's own delivery sink.
         *
         * Returns the endpoint, whose `ref` is the link target the catch-up
         * unicast addresses.
         */
        fun linkQTo(exposure: String, collector: DeltaCollectorCell): FanInlet<Propagate<SetDelta<String>>> {
            val remote = (HostedCellProxy.create(collector.ref, registryP, DeltaInletProxy::class.java)
                    as DeltaInletProxy).inlet.call
            val endpoint = FanInlet.create<Propagate<SetDelta<String>>>()
            endpoint.serve(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) = remote.propagate(value)
            })
            registryP.deliver(
                HostedPortInvocation(
                    cellRef = membraneRef,
                    portName = exposure,
                    type = HostedPortInvocation.Type.PORT_MANAGEMENT,
                    invocation = Invocation.of(LINK_TO, arrayOf(endpoint)),
                    peer = PeerId("q"),
                )
            )
            controller.runToIdle()
            return endpoint
        }

        /**
         * Peer q asserts [attention] on [exposure] across the *real* wire: a
         * `PORT_PROTOCOL` frame handed to the peering's Q→P egress, encoded,
         * decoded by P's [BridgeIngressCell] (which stamps `q`) and delivered
         * through P's host to the exposure's [ProtocolSupport].
         */
        fun assertAttentionFromQ(exposure: String, attention: Attention) {
            val edge = WireEdgeLink(
                id = UUID.randomUUID(),
                from = PortRef.generate(),
                to = PortRef.generate(membraneRef),
                fromAddr = PortAddress(CellRef(UUID.randomUUID()), "inlet"),
                toAddr = PortAddress(membraneRef, exposure),
            )
            loopback.bToA.deliver(
                HostedPortInvocation(
                    cellRef = membraneRef,
                    portName = exposure,
                    type = HostedPortInvocation.Type.PORT_PROTOCOL,
                    invocation = Invocation("", emptyList(), emptyList()),
                    protocolId = Protocols.Attention,
                    protocolLink = edge,
                    protocolMessage = attention,
                )
            )
            controller.runToIdle()
        }

        companion object {
            /** `LinkTo.linkTo(LinkFrom)` — the handshake-running overload, not the ad-hoc `Use` one. */
            val LINK_TO = LinkTo::class.java.methods.first {
                it.name == "linkTo" && it.parameterTypes.singleOrNull() == LinkFrom::class.java
            }
        }
    }

    @Test
    fun `BS-16 loopback - catch-up and live deltas cross projected, and the catch-up sees Peer(q, TransportVouched)`() {
        registerBridgeProjection()
        BridgedProjectionProbe.reset()
        val run = Run(seed = 1)

        // Pre-link state: what the catch-up baseline will carry (20/21 §Pull).
        run.membrane.projected.inlet.call.add("secret-one")
        run.membrane.projected.inlet.call.add("public-one")
        run.controller.runToIdle()

        val collector = run.collectorOnQ()
        run.linkQTo("projectedOutlet", collector)

        // The catch-up evaluation is the one BS-16's principal assertion is made
        // on: it ran inside the remote link request's dispatch, so the ambient
        // Principal is the ingress-stamped peer, at the only strength phase 1
        // certifies ([SEC1-01], [SEC1-24]).
        BridgedProjectionProbe.principals.size shouldBeGreaterThan 0
        BridgedProjectionProbe.principals.first() shouldBe
            Principal.Peer(PeerId("q"), AuthLevel.TransportVouched)

        val afterCatchUp = collector.received.size
        afterCatchUp shouldBeGreaterThan 0

        // A live delta after the link, through the SAME transform ([SEC1-17]).
        run.membrane.projected.inlet.call.add("secret-two")
        run.membrane.projected.inlet.call.add("public-two")
        run.controller.runToIdle()
        collector.received.size shouldBeGreaterThan afterCatchUp

        val disclosed = collector.received.flatMap { it.adds.keys }
        disclosed.none { it.startsWith("secret") } shouldBe true
        disclosed.toSet() shouldBe setOf("public-one", "public-two")

        run.deadLetters.shouldBeEmpty()
        run.hostP.supervisionAccounting().restarts shouldBe 0
    }

    @Test
    fun `BS-9 wire half - Deny discloses nothing across the bridge yet the peering stays usable`() {
        val run = Run(seed = 2)

        run.membrane.denied.inlet.call.add("anything")
        run.controller.runToIdle()

        val observed = mutableListOf<Attention>()
        ProtocolSupport.of(run.membrane.deniedOutlet).handle(Protocols.Attention) { _, message ->
            observed += message as Attention
        }

        val collector = run.collectorOnQ()
        val endpoint = run.linkQTo("deniedOutlet", collector)

        // The link IS established — Deny suppresses disclosure, it does not
        // refuse the peering ([SEC1-18]).
        run.membrane.deniedOutlet.linking.links.any { it.to == endpoint.ref } shouldBe true
        collector.received.shouldBeEmpty() // no catch-up crossed

        run.membrane.denied.inlet.call.add("also-nothing")
        run.controller.runToIdle()
        collector.received.shouldBeEmpty() // and no live delta crossed

        // ... while PORT_PROTOCOL traffic from the same peer still crosses the
        // bridge, arrives clamped to the declared ceiling and is applied — the
        // peering is usable for the metadata plane exactly as [SEC1-18]
        // requires, even though the data plane discloses nothing.
        run.assertAttentionFromQ("deniedOutlet", Attention(AttentionBand.HIGH.level))
        observed shouldContainExactly listOf(Attention(AttentionBand.LOW.level))

        run.hostP.supervisionAccounting().restarts shouldBe 0
    }

    /**
     * The clamp is a property of the **crossing**, not of the exposure: it must
     * fire for an assertion that arrived over the bridge and stay a no-op for
     * one raised in-process — on the same exposure, the same protocol and the
     * same asserted level. That second half is 93 I-28 §4.2's decided fast path
     * ("local crossings carry `LocalTrusted` and every predicate is a no-op"),
     * which is what keeps the seam free on the in-host path (P2).
     *
     * The two halves are checked back to back deliberately. Closing the wire
     * half meant making `ManagedHost` install the ingress-stamped peer around a
     * `PORT_PROTOCOL` delivery — previously `PORT_MANAGEMENT` only, so a
     * wire-arriving assertion was applied UNCLAMPED — and the failure mode to
     * guard against was trading one wrong principal for another by clamping
     * local assertions too. It cannot: the distinction is carried by the data
     * rather than inferred, since `HostedPortInvocation.peer` is non-null iff a
     * bridge ingress decoded the frame, and is never serialized. This test is
     * what holds that line; the whole-suite twin is `BoundaryPolicyTest`'s
     * `local attention crossing is never attenuated, even under a strict policy`.
     */
    @Test
    fun `the ceiling clamps a bridge-arriving assertion and stays a no-op for a local one`() {
        val run = Run(seed = 4)

        val observed = mutableListOf<Attention>()
        ProtocolSupport.of(run.membrane.deniedOutlet).handle(Protocols.Attention) { _, message ->
            observed += message as Attention
        }

        // Over the wire: the ingress stamp is ambient at the seam, so the
        // ceiling clamps (30/34 decision 6, `min(asserted, ceiling)`).
        run.assertAttentionFromQ("deniedOutlet", Attention(AttentionBand.HIGH.level))
        observed shouldContainExactly listOf(Attention(AttentionBand.LOW.level))

        // In-process, everything else equal: no peer is stamped, the principal
        // is LocalTrusted, the predicate is a no-op, the level is unattenuated.
        val localEdge = WireEdgeLink(
            id = UUID.randomUUID(),
            from = PortRef.generate(),
            to = PortRef.generate(run.membraneRef),
            fromAddr = PortAddress(CellRef(UUID.randomUUID()), "inlet"),
            toAddr = PortAddress(run.membraneRef, "deniedOutlet"),
        )
        ProtocolSupport.of(run.membrane.deniedOutlet)
            .deliver(Protocols.Attention, localEdge, Attention(AttentionBand.HIGH.level))
        run.controller.runToIdle()
        observed shouldContainExactly listOf(
            Attention(AttentionBand.LOW.level),
            Attention(AttentionBand.HIGH.level),
        )

        run.hostP.supervisionAccounting().restarts shouldBe 0
    }

    @Test
    fun `Deny suppressions on this path move the exposure's denial counter and restart nothing`() {
        val run = Run(seed = 3)
        val sink = run.membrane.boundaryDenials["deniedOutlet"]!!
        sink.denialCount shouldBe 0L

        run.membrane.denied.inlet.call.add("anything")
        run.controller.runToIdle()
        // No attachment yet: an emission nobody would have received attempts no
        // delivery and records nothing (per-attempt counting, computenet-usd.1).
        sink.denialCount shouldBe 0L

        val collector = run.collectorOnQ()
        run.linkQTo("deniedOutlet", collector)
        val afterCatchUp = sink.denialCount
        afterCatchUp shouldBe 1L // the suppressed catch-up unicast is one attempt

        run.membrane.denied.inlet.call.add("also-nothing")
        run.controller.runToIdle()
        sink.denialCount shouldBe afterCatchUp + 1L // one suppressed live attempt

        // BS-14: a denial is never a fault — RESTART is installed on this
        // membrane, so a reclassification would show here.
        run.hostP.supervisionAccounting().restarts shouldBe 0
    }
}
