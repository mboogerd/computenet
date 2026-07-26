package civictech.cell.membrane

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.attention.Attention
import civictech.cell.attention.AttentionBand
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.CurrentPeer
import civictech.cell.port.FanOutlet
import civictech.cell.link.LinkResult
import civictech.cell.link.PeerId
import civictech.cell.port.ProtocolSupport
import civictech.cell.port.Protocols
import civictech.cell.link.allowPeers
import civictech.cell.port.registerPort
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID
import civictech.cell.data.delta.SetDelta

/** Redacts any element whose key starts with "secret" — a named, registered `Delta -> Delta` transform (P9). */
private val redactSecrets = ProjectionId("redact-secrets-test")

@Suppress("UNCHECKED_CAST")
private fun registerRedactSecretsProjection() {
    ProjectionRegistry.register(redactSecrets) { delta ->
        val d = delta as SetDelta<String>
        SetDelta(
            adds = d.adds.filterKeys { !it.startsWith("secret") },
            dels = d.dels.filterKeys { !it.startsWith("secret") },
        )
    }
}

private class SetSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val outlet = registerPort("outlet", FanOutlet.create<SetOps<String>>())
}

private class DeltaCollector(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val received = mutableListOf<SetDelta<String>>()
    val inlet = registerPort(
        "inlet",
        civictech.cell.port.FanInlet.create<Propagate<SetDelta<String>>>(),
    )

    init {
        inlet.serve(object : Propagate<SetDelta<String>> {
            override fun propagate(value: SetDelta<String>) {
                received += value
            }
        })
    }
}

/** Composite exposing one organelle [SetCell]'s outlet under a declared [BoundaryPolicy]. */
private class DisclosureMembrane(
    val organelle: SetCell<String> = SetCell(),
    policy: BoundaryPolicy,
) : CompositeCell() {
    val exposedOutlet = mediateOutlet("exposedOutlet", "outlet", organelle.outlet, policy = policy)
    val inlet = flatten("inlet", "inlet", organelle.inlet)
}

/** Composite exposing one organelle outlet with a per-protocol attention ceiling. */
private class AttentionCeilingMembrane(
    val organelle: SetCell<String> = SetCell(),
    ceiling: AttentionBand,
) : CompositeCell() {
    val exposedOutlet = mediateOutlet(
        "exposedOutlet",
        "outlet",
        organelle.outlet,
        policy = BoundaryPolicy(
            protocolAuthority = mapOf(Protocols.Attention to ProtocolAuthority(ceiling = ceiling)),
        ),
    )
}

class BoundaryPolicyTest {

    @Test
    fun `disclosure filter covers catch-up and live emission uniformly`() {
        registerRedactSecretsProjection()
        val controller = SimulationController(seed = 10)
        val host = ManagedHost(scheduler = controller.scheduler())

        val membrane = DisclosureMembrane(policy = BoundaryPolicy(disclosure = DisclosurePolicy.Project(redactSecrets)))
        val membraneRef = host.managementInlet.call.spawn(membrane)

        // Pre-link state: the late-join catch-up baseline (20/21 §Pull) must
        // pass the SAME filter as the live stream.
        membrane.organelle.inlet.call.add("secret-one")
        membrane.organelle.inlet.call.add("public-one")
        controller.runToIdle()

        val collector = DeltaCollector()
        val collectorRef = host.managementInlet.call.spawn(collector)
        host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
            .shouldBeInstanceOf<LinkResult.Connected>()
        controller.runToIdle()

        // Live emission after the link, through the same filter.
        membrane.organelle.inlet.call.add("secret-two")
        membrane.organelle.inlet.call.add("public-two")
        controller.runToIdle()

        val allAddedKeys = collector.received.flatMap { it.adds.keys }
        allAddedKeys.none { it.startsWith("secret") } shouldBe true
        allAddedKeys.toSet() shouldBe setOf("public-one", "public-two")
    }

    @Test
    fun `disclosure Deny suppresses catch-up entirely, not only the live stream`() {
        val controller = SimulationController(seed = 11)
        val host = ManagedHost(scheduler = controller.scheduler())

        val membrane = DisclosureMembrane(policy = BoundaryPolicy(disclosure = DisclosurePolicy.Deny))
        val membraneRef = host.managementInlet.call.spawn(membrane)

        membrane.organelle.inlet.call.add("anything")
        controller.runToIdle()

        val collector = DeltaCollector()
        val collectorRef = host.managementInlet.call.spawn(collector)
        host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
            .shouldBeInstanceOf<LinkResult.Connected>()
        controller.runToIdle()

        membrane.organelle.inlet.call.add("also-nothing")
        controller.runToIdle()

        collector.received shouldBe emptyList()
    }

    @Test
    fun `attention ceiling clamps an asserted level before local handling`() {
        val controller = SimulationController(seed = 12)
        val host = ManagedHost(scheduler = controller.scheduler())

        val membrane = AttentionCeilingMembrane(ceiling = AttentionBand.LOW)
        val membraneRef = host.managementInlet.call.spawn(membrane)

        val collector = DeltaCollector()
        val collectorRef = host.managementInlet.call.spawn(collector)
        val link = (
            host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
                as LinkResult.Connected
            ).link

        val observed = mutableListOf<Attention>()
        ProtocolSupport.of(membrane.exposedOutlet).handle(Protocols.Attention) { _, message ->
            observed += message as Attention
        }

        // A REMOTE consumer asserts HIGH interest upstream (CurrentPeer
        // simulates the bridge-ingress stamp, G-29 phase 1); the boundary's
        // ceiling (LOW) MUST clamp it before this outlet's own handling sees
        // it — "attention is a request, not an entitlement" (30/34 decision
        // 6). A LocalTrusted assertion is deliberately exempt (93 I-28 §4.2)
        // — see the sibling `local attention crossing is never attenuated` test.
        CurrentPeer.with(PeerId("remote-consumer")) {
            Protocols.sendUpstream(link, Protocols.Attention, Attention(AttentionBand.HIGH.level))
        }
        controller.runToIdle()

        observed shouldBe listOf(Attention(AttentionBand.LOW.level))
    }

    @Test
    fun `remote attention below the protocol floor is refused, not merely unclamped`() {
        val controller = SimulationController(seed = 13)
        val host = ManagedHost(scheduler = controller.scheduler())

        val organelle = SetCell<String>()
        val membrane = object : CompositeCell() {
            val exposedOutlet = mediateOutlet(
                "exposedOutlet",
                "outlet",
                organelle.outlet,
                policy = BoundaryPolicy(
                    protocolAuthority = mapOf(
                        Protocols.Attention to ProtocolAuthority(minAuth = AuthLevel.Authenticated),
                    ),
                ),
            )
        }
        val membraneRef = host.managementInlet.call.spawn(membrane)
        val collector = DeltaCollector()
        val collectorRef = host.managementInlet.call.spawn(collector)
        val link = (
            host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
                as LinkResult.Connected
            ).link

        val observed = mutableListOf<Attention>()
        ProtocolSupport.of(membrane.exposedOutlet).handle(Protocols.Attention) { _, message ->
            observed += message as Attention
        }

        // A remote peer is TransportVouched (phase 1, no keys/DIDs yet, 95
        // §R7); a floor demanding Authenticated refuses it outright.
        CurrentPeer.with(PeerId("remote-consumer")) {
            Protocols.sendUpstream(link, Protocols.Attention, Attention(AttentionBand.HIGH.level))
        }
        controller.runToIdle()

        observed shouldBe emptyList()
    }

    @Test
    fun `local attention crossing is never attenuated, even under a strict policy`() {
        val controller = SimulationController(seed = 14)
        val host = ManagedHost(scheduler = controller.scheduler())

        // A maximally strict policy (Authenticated floor, NONE ceiling) —
        // still a no-op for LocalTrusted (93 I-28 §4.2: "Local crossings
        // carry LocalTrusted and every predicate is a no-op", the P7
        // near-zero-cost fast path).
        val organelle = SetCell<String>()
        val membrane = object : CompositeCell() {
            val exposedOutlet = mediateOutlet(
                "exposedOutlet",
                "outlet",
                organelle.outlet,
                policy = BoundaryPolicy(
                    protocolAuthority = mapOf(
                        Protocols.Attention to ProtocolAuthority(
                            minAuth = AuthLevel.Authenticated,
                            ceiling = AttentionBand.NONE,
                        ),
                    ),
                ),
            )
        }
        val membraneRef = host.managementInlet.call.spawn(membrane)
        val collector = DeltaCollector()
        val collectorRef = host.managementInlet.call.spawn(collector)
        val link = (
            host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
                as LinkResult.Connected
            ).link

        val observed = mutableListOf<Attention>()
        ProtocolSupport.of(membrane.exposedOutlet).handle(Protocols.Attention) { _, message ->
            observed += message as Attention
        }

        Protocols.sendUpstream(link, Protocols.Attention, Attention(AttentionBand.HIGH.level))
        controller.runToIdle()

        observed shouldBe listOf(Attention(AttentionBand.HIGH.level))
    }

    @Test
    fun `seam 2, link-time authority rejects a non-allowlisted peer and admits a local request`() {
        // Seam 2 (10/13 §handshake) always evaluates the TARGET port's own
        // `linking.policies` — for an exposed organelle INLET the target is
        // this membrane's own port, so `mediate()`'s `linkAuthority` is where
        // this seam is fully realized (an exposed OUTLET's target is the
        // external consumer's own inlet, outside the membrane, per
        // `mediateOutlet`'s doc comment).
        val controller = SimulationController(seed = 15)
        val host = ManagedHost(scheduler = controller.scheduler())

        val organelle = SetCell<String>()
        val membrane = object : CompositeCell() {
            val exposedInlet = mediate(
                "exposedInlet",
                "inlet",
                organelle.inlet,
                policy = BoundaryPolicy(linkAuthority = listOf(allowPeers(PeerId("trusted-peer")))),
            )
        }
        val membraneRef = host.managementInlet.call.spawn(membrane)
        val source = SetSource()
        val sourceRef = host.managementInlet.call.spawn(source)

        CurrentPeer.with(PeerId("untrusted-peer")) {
            host.managementInlet.call.connect(sourceRef, "outlet", membraneRef, "exposedInlet")
        }.shouldBeInstanceOf<LinkResult.Rejected>()

        // Local (no stamped peer) requests are unaffected by the allowlist.
        host.managementInlet.call.connect(sourceRef, "outlet", membraneRef, "exposedInlet")
            .shouldBeInstanceOf<LinkResult.Connected>()
    }
}
