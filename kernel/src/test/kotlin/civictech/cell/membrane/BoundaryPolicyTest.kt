package civictech.cell.membrane

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.control.Attention
import civictech.cell.control.AttentionBand
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.DeadLetter
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.SupervisionPolicy
import civictech.cell.link.CurrentPeer
import civictech.cell.port.FanOutlet
import civictech.cell.link.LinkResult
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.link.allowPeers
import civictech.cell.port.registerPort
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

    @Test
    fun `BS-2 a denied link-authority peer lands one denial record and the BS-1 allowed twin leaves no trace`() {
        // computenet-usd.1.5: installLinkAuthority wraps each installed
        // LinkPolicy so a Rejected verdict is accounted (seam=LINK_AUTHORITY,
        // principal=the refused peer, detail=the refusing policy's own
        // rejection reason) BEFORE it is returned to the handshake — the
        // handshake's own control flow (reject before install/register) is
        // untouched, so a denial still leaves no half-registered port and no
        // subscriber entry ([SEC1-08][SEC1-09][SEC1-29]).
        val controller = SimulationController(seed = 16)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(
            Use.fixed(
                object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        letters += value
                    }
                },
                PortRef.generate(),
            ),
        )

        val organelle = SetCell<String>()
        val membrane = object : CompositeCell() {
            val exposedInlet = mediate(
                "exposedInlet",
                "inlet",
                organelle.inlet,
                policy = BoundaryPolicy(linkAuthority = listOf(allowPeers(PeerId("alice")))),
            )
        }
        val membraneRef = host.managementInlet.call.spawn(membrane)
        // RESTART, so BS-14 ("a denial is not a fault") is a real check
        // rather than a vacuous one — were this denial misclassified as a
        // cell failure anywhere on this path, this is the policy that would
        // fire (mirrors BoundaryDenialAccountingTest's precedent).
        host.managementInlet.call.supervise(membraneRef, SupervisionPolicy.RESTART)
        val source = SetSource()
        val sourceRef = host.managementInlet.call.spawn(source)
        controller.runToIdle()

        val sink = membrane.boundaryDenials["exposedInlet"]!!
        sink.denialCount shouldBe 0L

        // BS-2: CurrentPeer=mallory is rejected.
        val rejected = CurrentPeer.with(PeerId("mallory")) {
            host.managementInlet.call.connect(sourceRef, "outlet", membraneRef, "exposedInlet")
        }
        controller.runToIdle()
        rejected.shouldBeInstanceOf<LinkResult.Rejected>()

        // Exactly one denial record, naming mallory and the refusing policy.
        sink.denialCount shouldBe 1L
        letters.size shouldBe 1
        val letter = letters.single()
        letter.cause shouldBe null
        letter.description shouldContain "exposedInlet"
        letter.description shouldContain "mallory"
        letter.description shouldContain "LINK_REFUSED"
        letter.description shouldContain "not on the allowlist"

        // No port half-registered, no subscriber entry: the rejected request
        // never reached install()/register().
        membrane.exposedInlet.linking.links shouldBe emptyList()

        // BS-14: not a fault — no RESTART, no supervision escalation.
        host.supervisionAccounting().restarts shouldBe 0L
        // No wave minted or advanced: the synthesized report carries a null context.
        letter.invocation!!.invocation.context shouldBe null

        // BS-1 twin: CurrentPeer=alice links successfully, no new record, counter unchanged.
        val connected = CurrentPeer.with(PeerId("alice")) {
            host.managementInlet.call.connect(sourceRef, "outlet", membraneRef, "exposedInlet")
        }
        controller.runToIdle()
        connected.shouldBeInstanceOf<LinkResult.Connected>()

        sink.denialCount shouldBe 1L
        letters.size shouldBe 1
        membrane.exposedInlet.linking.links.size shouldBe 1
    }
}
