package civictech.cell.membrane

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Frozen
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.Redacted
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
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
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

/**
 * The same redaction, but one that also exercises the *suppressing* half of
 * [DisclosurePolicy.Project]: a delta with nothing left to disclose after
 * redaction returns null, which suppresses that particular delivery entirely
 * (spec 40/43 seam 3) and is therefore an accounted denial
 * (`DISCLOSURE_PROJECTED_AWAY`).
 */
private val redactSecretsOrSuppress = ProjectionId("redact-secrets-or-suppress-test")

@Suppress("UNCHECKED_CAST")
private fun registerSuppressingProjection() {
    ProjectionRegistry.register(redactSecretsOrSuppress) { delta ->
        val d = delta as SetDelta<String>
        val adds = d.adds.filterKeys { !it.startsWith("secret") }
        val dels = d.dels.filterKeys { !it.startsWith("secret") }
        if (adds.isEmpty() && dels.isEmpty()) null else SetDelta(adds = adds, dels = dels)
    }
}

/**
 * A contract whose emission carries exclusives ([Owned]/[Leased]) — the shape
 * that makes the disclosure seam's payload boundary checkable: a *suppressed*
 * delivery must still route its arguments through the host's spec-23-R8
 * sanitizer rather than dropping a live handle on the floor.
 */
interface ExclusiveDrop {
    fun send(owned: Owned<String>, leased: Leased<String>)
}

/** A membrane whose only exposure is a `Deny`-disclosed outlet of [ExclusiveDrop]. */
private class ExclusiveMembrane : CompositeCell() {
    private val organelleOutlet = FanOutlet.create<ExclusiveDrop>()
    val exposedOutlet = mediateOutlet(
        "exposedOutlet",
        "outlet",
        organelleOutlet,
        policy = BoundaryPolicy(disclosure = DisclosurePolicy.Deny),
    )
}

/**
 * BS-7's probe contract ([SEC1-19]): one emitted [Owned], so "the transform ran
 * once per emission" and "the payload was consumed at most once across every
 * path" become one test rather than two.
 */
interface ExclusiveFeed {
    fun send(owned: Owned<String>)
}

/**
 * A projection that **counts its own invocations** — BS-7 asserts that count
 * directly rather than any scheduling timing — and that reads its delta the way
 * D2 (`computenet-usd.2.2`) says a projection must: [Owned.borrow], never
 * [Owned.take]. It forwards the very same wrapper, so the sole consumer's
 * `take()` downstream is the one consumption the SPSC rule allocates.
 */
private val borrowCountingProjection = ProjectionId("borrow-counting-projection-test")

private val borrowCountingProjectionCalls = AtomicInteger()

private fun registerBorrowCountingProjection() {
    ProjectionRegistry.register(borrowCountingProjection) { delta ->
        borrowCountingProjectionCalls.incrementAndGet()
        (delta as Owned<*>).borrow()
        delta
    }
}

/** A membrane whose only exposure is a [borrowCountingProjection]-projected outlet of [ExclusiveFeed]. */
private class ProjectedExclusiveMembrane : CompositeCell() {
    private val organelleOutlet = FanOutlet.create<ExclusiveFeed>()
    val exposedOutlet = mediateOutlet(
        "exposedOutlet",
        "outlet",
        organelleOutlet,
        policy = BoundaryPolicy(disclosure = DisclosurePolicy.Project(borrowCountingProjection)),
    )
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

/**
 * Composite exposing one organelle outlet under producer-side subscribe
 * authority ([SEC1-12], option (a)). [mediateOutlet] demands a flow-time
 * predicate, so `linkAuthority` rides alongside the cheapest one that changes
 * nothing these tests observe (an attention ceiling on a protocol nobody sends).
 */
private class SubscribeAuthorityMembrane(
    val organelle: SetCell<String> = SetCell(),
    allowed: PeerId,
) : CompositeCell() {
    val exposedOutlet = mediateOutlet(
        "exposedOutlet",
        "outlet",
        organelle.outlet,
        policy = BoundaryPolicy(
            linkAuthority = listOf(allowPeers(allowed)),
            protocolAuthority = mapOf(Protocols.Attention to ProtocolAuthority(ceiling = AttentionBand.LOW)),
        ),
    )
}

/** The same producer-side authority declared through [flatten] — `linkAuthority` alone never forces Mediate. */
private class FlattenedAuthorityMembrane(
    val organelle: SetCell<String> = SetCell(),
    allowed: PeerId,
) : CompositeCell() {
    val exposedOutlet = flatten(
        "exposedOutlet",
        "outlet",
        organelle.outlet,
        policy = BoundaryPolicy(linkAuthority = listOf(allowPeers(allowed))),
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
    fun `BS-10 attention ceiling clamps an asserted level and emits no denial record or counter movement`() {
        val controller = SimulationController(seed = 12)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)

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

        val sink = membrane.boundaryDenials["exposedOutlet"]!!
        sink.denialCount shouldBe 0L

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
        // computenet-usd.1.3: a clamp is not a denial (30/34 decision 6) — no
        // record, no counter movement, no dead letter.
        sink.denialCount shouldBe 0L
        letters shouldBe emptyList()
    }

    @Test
    fun `remote attention below the protocol floor is refused before any handler runs, with one MIN_AUTH denial record`() {
        val controller = SimulationController(seed = 13)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)

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
        // RESTART, so BS-14 ("a denial is not a fault") is a real check here
        // too, rather than a vacuous one.
        host.managementInlet.call.supervise(membraneRef, SupervisionPolicy.RESTART)
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

        // Refused before any handler runs — this exposure's own attention
        // handler never sees it.
        observed shouldBe emptyList()

        // computenet-usd.1.3: the minAuth refusal is accounted, not a silent
        // drop ([SEC1-13][SEC1-14][SEC1-16]).
        val sink = membrane.boundaryDenials["exposedOutlet"]!!
        sink.denialCount shouldBe 1L
        letters.size shouldBe 1
        val letter = letters.single()
        letter.cause shouldBe null
        letter.description shouldContain "exposedOutlet"
        letter.description shouldContain "remote-consumer"
        letter.description shouldContain "MIN_AUTH"
        letter.description shouldContain Protocols.Attention.name

        // BS-14: not a fault.
        host.supervisionAccounting().restarts shouldBe 0L
        letter.invocation!!.invocation.context shouldBe null
    }

    @Test
    fun `BS-11 rate refusal is accounted per-Principal - alice over the window is denied, bob is unaffected`() {
        // computenet-usd.1.3: protocolAuthority.asProtocolFilter's
        // ratePerWindow branch is now accounted (reason=RATE, subject the
        // ProtocolId, principal the refused Peer.id). Rate is counted per
        // (ProtocolId, Principal) — alice's window never touches bob's count
        // or names him in a record ([SEC1-13][SEC1-14][SEC1-16]).
        val controller = SimulationController(seed = 21)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)

        val organelle = SetCell<String>()
        val membrane = object : CompositeCell() {
            val exposedOutlet = mediateOutlet(
                "exposedOutlet",
                "outlet",
                organelle.outlet,
                policy = BoundaryPolicy(
                    protocolAuthority = mapOf(
                        Protocols.Attention to ProtocolAuthority(ratePerWindow = 2),
                    ),
                ),
            )
        }
        val membraneRef = host.managementInlet.call.spawn(membrane)
        // RESTART, so BS-14 is a real check here too, not a vacuous one.
        host.managementInlet.call.supervise(membraneRef, SupervisionPolicy.RESTART)
        val collector = DeltaCollector()
        val collectorRef = host.managementInlet.call.spawn(collector)
        val link = (
            host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
                as LinkResult.Connected
            ).link

        // Distinguish alice's/bob's delivered frames by version, since the
        // handler itself does not see the principal.
        val observedVersions = mutableListOf<Long>()
        ProtocolSupport.of(membrane.exposedOutlet).handle(Protocols.Attention) { _, message ->
            observedVersions += (message as Attention).version
        }

        val alice = PeerId("alice")
        val bob = PeerId("bob")
        fun sendAs(peer: PeerId, version: Long) {
            CurrentPeer.with(peer) {
                Protocols.sendUpstream(link, Protocols.Attention, Attention(AttentionBand.HIGH.level, version))
            }
        }

        // alice sends k+2 = 4 frames, bob sends k = 2 — interleaved, so a
        // shared (not per-Principal) counter would wrongly refuse bob's
        // second frame too.
        sendAs(alice, 1)
        sendAs(bob, 101)
        sendAs(alice, 2)
        sendAs(bob, 102)
        sendAs(alice, 3)
        sendAs(alice, 4)
        controller.runToIdle()

        // alice's first two (at/under the window) delivered, last two
        // refused; both of bob's delivered.
        observedVersions.toSet() shouldBe setOf(1L, 2L, 101L, 102L)

        val sink = membrane.boundaryDenials["exposedOutlet"]!!
        sink.denialCount shouldBe 2L
        letters.size shouldBe 2
        letters.forEach { letter ->
            letter.cause shouldBe null
            letter.description shouldContain "exposedOutlet"
            letter.description shouldContain "alice"
            letter.description shouldContain "RATE"
            letter.description shouldContain Protocols.Attention.name
            // BS-14 arm: no wave minted or advanced by reporting a refusal.
            letter.invocation!!.invocation.context shouldBe null
        }
        letters.none { it.description.contains("bob") } shouldBe true

        // BS-14: not a fault.
        host.supervisionAccounting().restarts shouldBe 0L
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
    fun `seam 2, producer-side authority refuses an unauthorized subscriber and leaves no subscriber entry`() {
        // [SEC1-12], option (a): the handshake evaluates the SOURCE port's own
        // `linking.policies` as well as the target's, so a producing membrane
        // can refuse a subscriber even though the handshake TARGET is the
        // consumer's own (external) inlet.
        val controller = SimulationController(seed = 16)
        val host = ManagedHost(scheduler = controller.scheduler())

        val membrane = SubscribeAuthorityMembrane(allowed = PeerId("alice"))
        val membraneRef = host.managementInlet.call.spawn(membrane)
        membrane.organelle.inlet.call.add("before-anyone-subscribes")
        controller.runToIdle()

        val collector = DeltaCollector()
        val collectorRef = host.managementInlet.call.spawn(collector)

        val rejected = CurrentPeer.with(PeerId("mallory")) {
            host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
        }.shouldBeInstanceOf<LinkResult.Rejected>()
        // The refusing policy is named (93 I-28 §4.4 rule 1: refusals are visible).
        rejected.reason shouldContain "mallory"
        rejected.reason shouldContain "allowlist"

        // No partially-established topology survives the refusal ([SEC1-09]
        // pattern): no link registered on the producer, and no delivery.
        membrane.exposedOutlet.linking.links shouldBe emptyList()
        membrane.organelle.inlet.call.add("after-the-refusal")
        controller.runToIdle()
        collector.received shouldBe emptyList()

        // Default-open posture is preserved: a local request (null identity)
        // carries no peer and is not gated ([SEC1-02], spec 43).
        host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
            .shouldBeInstanceOf<LinkResult.Connected>()
    }

    @Test
    fun `seam 2, producer-side authority admits an allowlisted peer and traffic flows`() {
        val controller = SimulationController(seed = 17)
        val host = ManagedHost(scheduler = controller.scheduler())

        val membrane = SubscribeAuthorityMembrane(allowed = PeerId("alice"))
        val membraneRef = host.managementInlet.call.spawn(membrane)

        val collector = DeltaCollector()
        val collectorRef = host.managementInlet.call.spawn(collector)

        CurrentPeer.with(PeerId("alice")) {
            host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
        }.shouldBeInstanceOf<LinkResult.Connected>()
        controller.runToIdle()

        membrane.organelle.inlet.call.add("for-alice")
        controller.runToIdle()

        collector.received.flatMap { it.adds.keys } shouldBe listOf("for-alice")
    }

    @Test
    fun `seam 2, a negotiated tap on a producer-authorized outlet is gated the same way`() {
        // PN-10/PN-12: `tap(negotiated = true)` routes through the very same
        // handshake with the outlet as `portOut`, so the producer-side gate
        // covers the Observe role too.
        val controller = SimulationController(seed = 18)
        val host = ManagedHost(scheduler = controller.scheduler())

        val membrane = SubscribeAuthorityMembrane(allowed = PeerId("alice"))
        host.managementInlet.call.spawn(membrane)

        val observer = DeltaCollector()
        host.managementInlet.call.spawn(observer)

        CurrentPeer.with(PeerId("mallory")) {
            membrane.exposedOutlet.tap(observer.inlet)
        }.shouldBeInstanceOf<LinkResult.Rejected>()

        membrane.organelle.inlet.call.add("not-for-mallory")
        controller.runToIdle()
        observer.received shouldBe emptyList()

        CurrentPeer.with(PeerId("alice")) {
            membrane.exposedOutlet.tap(observer.inlet)
        }.shouldBeInstanceOf<LinkResult.Connected>()
        membrane.organelle.inlet.call.add("for-alice")
        controller.runToIdle()
        // Alice's admitted tap gets the `onLinked` catch-up baseline (20/21
        // §Pull) as well as the live element — including the one added while
        // mallory was refused. The link-time gate decides WHO attaches; it is
        // not a redaction of what an admitted subscriber then sees (that is
        // `disclosure`'s seam).
        observer.received.flatMap { it.adds.keys }.toSet() shouldBe setOf("not-for-mallory", "for-alice")
    }

    @Test
    fun `seam 2, a flattened outlet's link-time authority refuses a non-allowlisted peer`() {
        // `flatten`'s KDoc claims "authority is link-time onLink policies only"
        // — for an exposed OUTLET that claim is only true once the source side
        // of the handshake is evaluated.
        val controller = SimulationController(seed = 19)
        val host = ManagedHost(scheduler = controller.scheduler())

        val membrane = FlattenedAuthorityMembrane(allowed = PeerId("alice"))
        val membraneRef = host.managementInlet.call.spawn(membrane)
        val collector = DeltaCollector()
        val collectorRef = host.managementInlet.call.spawn(collector)

        CurrentPeer.with(PeerId("mallory")) {
            host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
        }.shouldBeInstanceOf<LinkResult.Rejected>()

        host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
            .shouldBeInstanceOf<LinkResult.Connected>()
        controller.runToIdle()
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

    /** Collects everything the host dead-letters, so a denial's report is readable in-test. */
    private fun collectDeadLetters(host: ManagedHost): MutableList<DeadLetter> {
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
        return letters
    }

    @Test
    fun `BS-8 catch-up and live emission are redacted by the same transform, with denials accounted on both paths`() {
        // computenet-usd.1.4: DisclosurePolicy.asDeltaFilter accounts every
        // suppressed delivery attempt through the exposure's
        // BoundaryDenialSink ([SEC1-25][SEC1-26]). BS-8's claim is that the
        // onLinked catch-up unicast and the live stream are ONE filter (20/21
        // §Pull, 93 I-28: "a snapshot IS a delta") — so both must redact by the
        // same transform AND both must account what they suppress.
        //
        // Concord note ([SEC1-30]): 43-security.md carries no normative EARS
        // ids, so this property is pinned here and filed as an uncovered
        // candidate in concord/corpus/DISPUTES.md — never as an invented
        // `covers:` id.
        registerSuppressingProjection()
        val controller = SimulationController(seed = 17)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)

        val membrane = DisclosureMembrane(
            policy = BoundaryPolicy(disclosure = DisclosurePolicy.Project(redactSecretsOrSuppress)),
        )
        val membraneRef = host.managementInlet.call.spawn(membrane)
        // RESTART installed so BS-14 ("a denial is not a fault") is a real
        // check on this seam too, not a vacuous one.
        host.managementInlet.call.supervise(membraneRef, SupervisionPolicy.RESTART)
        controller.runToIdle()
        val sink = membrane.boundaryDenials["exposedOutlet"]!!

        // Pre-link state, with nobody attached: an emission that attempts no
        // delivery suppresses nothing and records nothing (the per-attempt
        // counting rule, stated from its zero end).
        membrane.organelle.inlet.call.add("secret-one")
        controller.runToIdle()
        sink.denialCount shouldBe 0L

        // 1. CATCH-UP PATH. A subscriber joins while the whole state projects
        //    away: the state-as-delta unicast is suppressed, and that single
        //    attempt is one denial record.
        val collectorA = DeltaCollector()
        val collectorARef = host.managementInlet.call.spawn(collectorA)
        host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorARef, "inlet")
            .shouldBeInstanceOf<LinkResult.Connected>()
        controller.runToIdle()
        sink.denialCount shouldBe 1L
        collectorA.received shouldBe emptyList()

        // 2. LIVE PATH. A live delta that projects away is suppressed for the
        //    one attached consumer — one attempt, one further record.
        membrane.organelle.inlet.call.add("secret-two")
        controller.runToIdle()
        sink.denialCount shouldBe 2L

        // A live delta that survives projection is delivered, redacted.
        membrane.organelle.inlet.call.add("public-one")
        controller.runToIdle()

        // 3. A second subscriber's catch-up carries the whole (mixed) state and
        //    arrives redacted by the SAME transform — secrets stripped, the
        //    public element present, no extra denial.
        val collectorB = DeltaCollector()
        val collectorBRef = host.managementInlet.call.spawn(collectorB)
        host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorBRef, "inlet")
            .shouldBeInstanceOf<LinkResult.Connected>()
        controller.runToIdle()

        membrane.organelle.inlet.call.add("public-two")
        controller.runToIdle()

        // No un-redacted value observable on EITHER path.
        val seenByA = collectorA.received.flatMap { it.adds.keys }
        val seenByB = collectorB.received.flatMap { it.adds.keys }
        (seenByA + seenByB).none { it.startsWith("secret") } shouldBe true
        seenByA.toSet() shouldBe setOf("public-one", "public-two")
        seenByB.toSet() shouldBe setOf("public-one", "public-two")

        // Exactly the two suppressions above were accounted — a delivered
        // (merely redacted) emission is not a denial.
        sink.denialCount shouldBe 2L
        letters.size shouldBe 2
        letters.forEach { letter ->
            letter.cause shouldBe null
            letter.description shouldContain "exposedOutlet"
            letter.description shouldContain "DISCLOSURE"
            letter.description shouldContain "DISCLOSURE_PROJECTED_AWAY"
            letter.description shouldContain redactSecretsOrSuppress.name
            // BS-14 arm: no wave minted or advanced by reporting a refusal.
            letter.invocation!!.invocation.context shouldBe null
        }
        // The refused delta reaches the host's own audit fan-out (that is the
        // point of accounting it) — it is the SUBSCRIBER-facing paths above
        // that must never see it.
        letters.first().invocation!!.invocation.args.size shouldBe 1

        // BS-14: a denial is not a fault.
        host.supervisionAccounting().restarts shouldBe 0L
    }

    @Test
    fun `BS-9 disclosure Deny still links and still clamps an attention assertion`() {
        // Deny is a state-disclosure refusal, not a refusal to peer: a
        // management-/attention-only peering remains a first-class arrangement
        // (93 I-28). The link succeeds, no state crosses, and the asserted
        // attention is clamped and applied — a clamp is not a denial (30/34
        // decision 6), so it moves no counter.
        val controller = SimulationController(seed = 18)
        val host = ManagedHost(scheduler = controller.scheduler())

        val organelle = SetCell<String>()
        val membrane = object : CompositeCell() {
            val exposedOutlet = mediateOutlet(
                "exposedOutlet",
                "outlet",
                organelle.outlet,
                policy = BoundaryPolicy(
                    disclosure = DisclosurePolicy.Deny,
                    protocolAuthority = mapOf(
                        Protocols.Attention to ProtocolAuthority(ceiling = AttentionBand.LOW),
                    ),
                ),
            )
        }
        val membraneRef = host.managementInlet.call.spawn(membrane)
        organelle.inlet.call.add("private-state")
        controller.runToIdle()

        val collector = DeltaCollector()
        val collectorRef = host.managementInlet.call.spawn(collector)
        val link = (
            host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
                as LinkResult.Connected
            ).link
        controller.runToIdle()

        val sink = membrane.boundaryDenials["exposedOutlet"]!!
        // The link stands, and the suppressed catch-up is accounted (one
        // attempt, one record) rather than dropped silently.
        sink.denialCount shouldBe 1L

        val observed = mutableListOf<Attention>()
        ProtocolSupport.of(membrane.exposedOutlet).handle(Protocols.Attention) { _, message ->
            observed += message as Attention
        }
        CurrentPeer.with(PeerId("remote-consumer")) {
            Protocols.sendUpstream(link, Protocols.Attention, Attention(AttentionBand.HIGH.level))
        }
        controller.runToIdle()

        // Clamped and applied, on a peering that discloses nothing.
        observed shouldBe listOf(Attention(AttentionBand.LOW.level))
        // A clamp is not a refusal: the counter did not move for it.
        sink.denialCount shouldBe 1L

        // And no state crosses, live or catch-up.
        organelle.inlet.call.add("still-private")
        controller.runToIdle()
        collector.received shouldBe emptyList()
        sink.denialCount shouldBe 2L
    }

    @Test
    fun `one denial record per suppressed delivery attempt - per consumer, per tap, per observer`() {
        // The DECIDED counting semantics (feature computenet-usd.1): the unit
        // is the delivery ATTEMPT, not the emission — one attempt per consumer,
        // per typed tap, per observer notification and per targeted catch-up
        // unicast, so a boundary that suppressed N deliveries reports exactly N.
        //
        // That used to be a free consequence of FanOutlet evaluating
        // disclosureFilter once per ATTEMPT. Since computenet-usd.2.2 it
        // evaluates at most once per EMISSION ([SEC1-19]) and accounts every
        // further suppressed attempt through FanOutlet.onRepeatSuppression, so
        // the counts asserted below are unchanged while the mechanism behind
        // them is not. Nothing here was weakened for that change: only this
        // comment's description of the mechanism was corrected (review of
        // computenet-usd.2.2).
        val controller = SimulationController(seed = 19)
        val host = ManagedHost(scheduler = controller.scheduler())

        val membrane = DisclosureMembrane(policy = BoundaryPolicy(disclosure = DisclosurePolicy.Deny))
        val membraneRef = host.managementInlet.call.spawn(membrane)
        controller.runToIdle()
        val sink = membrane.boundaryDenials["exposedOutlet"]!!

        // Nobody attached: an emission attempts no delivery, so it suppresses
        // none and records none.
        membrane.organelle.inlet.call.add("one")
        controller.runToIdle()
        sink.denialCount shouldBe 0L

        // One consumer, over a real link — its catch-up unicast is itself one
        // suppressed attempt.
        val collector = DeltaCollector()
        val collectorRef = host.managementInlet.call.spawn(collector)
        host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
            .shouldBeInstanceOf<LinkResult.Connected>()
        controller.runToIdle()
        sink.denialCount shouldBe 1L

        // Plus a contract-typed tap and a payload-agnostic observer: three
        // attachments, hence three delivery attempts per emission.
        val tapped = mutableListOf<SetDelta<String>>()
        membrane.exposedOutlet.tap(
            Use.fixed(
                object : Propagate<SetDelta<String>> {
                    override fun propagate(value: SetDelta<String>) {
                        tapped += value
                    }
                },
                PortRef.generate(),
            ),
        )
        var observedEmissions = 0
        membrane.exposedOutlet.observe(PortRef.generate()) { observedEmissions++ }

        val before = sink.denialCount
        membrane.organelle.inlet.call.add("two")
        controller.runToIdle()
        sink.denialCount - before shouldBe 3L

        // A second emission adds three more: the count tracks attempts, not
        // emissions and not attachments-at-first-refusal.
        membrane.organelle.inlet.call.add("three")
        controller.runToIdle()
        sink.denialCount - before shouldBe 6L

        // Nothing crossed on any of the three shapes.
        collector.received shouldBe emptyList()
        tapped shouldBe emptyList()
        observedEmissions shouldBe 0
    }

    @Test
    fun `a suppressed attempt carrying exclusives reaches the dead-letter fan-out only in Frozen or Redacted form`() {
        // The exclusive-payload boundary with feature computenet-usd.2: this
        // seam adds NO discharge logic of its own — a suppressed attempt's
        // arguments ride to the sink, and the host's spec-23-R8 sanitizer
        // (inherited from DeadLetters, never reimplemented) is the single
        // place Owned/Leased are converted. What is asserted here is only that
        // no live exclusive handle enters the dead-letter fan-out; exactly-once
        // discharge under repeated filter evaluation is usd.2's subject and is
        // deliberately not asserted.
        val controller = SimulationController(seed = 20)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)

        val membrane = ExclusiveMembrane()
        host.managementInlet.call.spawn(membrane)
        controller.runToIdle()

        var delivered = false
        membrane.exposedOutlet.subscribe(
            Use.fixed(
                object : ExclusiveDrop {
                    override fun send(owned: Owned<String>, leased: Leased<String>) {
                        delivered = true
                    }
                },
                PortRef.generate(),
            ),
        )

        membrane.exposedOutlet.call.send(Owned("owned-secret"), Leased("leased-secret"))
        controller.runToIdle()

        delivered shouldBe false
        membrane.boundaryDenials["exposedOutlet"]!!.denialCount shouldBe 1L
        letters.size shouldBe 1
        val captured = letters.single().invocation!!.invocation.args
        captured.size shouldBe 2
        captured[0].shouldBeInstanceOf<Frozen<*>>().value shouldBe "owned-secret"
        captured[1].shouldBeInstanceOf<Redacted>()
        captured.none { it is Owned<*> || it is Leased<*> } shouldBe true
        letters.single().description shouldContain "DISCLOSURE_DENIED"
    }

    @Test
    fun `BS-7 a Project transform runs at most once per emission across tap, observer and consumer`() {
        // [SEC1-19], decided realization D1 (computenet-usd.2.2): FanOutlet
        // evaluates its disclosureFilter LAZILY, at most once per emission, and
        // shares that one verdict with every typed tap, every payload-agnostic
        // observer and the consumer. "At most", not "exactly": zero attachments
        // is zero evaluations.
        //
        // Against the unfixed code this reads 3 after one emission (once per
        // consumer in invoke(), once per typed tap in invoke(), once per
        // observer in notifyObserver()) — the filter was evaluated per delivery
        // ATTEMPT.
        registerBorrowCountingProjection()
        borrowCountingProjectionCalls.set(0)
        val controller = SimulationController(seed = 21)
        val host = ManagedHost(scheduler = controller.scheduler())

        val membrane = ProjectedExclusiveMembrane()
        host.managementInlet.call.spawn(membrane)
        controller.runToIdle()

        // Nobody attached: no delivery is attempted, so the transform runs not
        // at all — the lazy half of "at most once".
        membrane.exposedOutlet.call.send(Owned("nobody-home"))
        controller.runToIdle()
        borrowCountingProjectionCalls.get() shouldBe 0

        // Three attachments of all three shapes. A tap borrows (spec 23 §Taps);
        // the sole consumer takes, which is the one consumption SPSC allocates.
        val borrowed = mutableListOf<String>()
        val taken = mutableListOf<String>()
        var observedEmissions = 0
        membrane.exposedOutlet.tap(
            Use.fixed(
                object : ExclusiveFeed {
                    override fun send(owned: Owned<String>) {
                        borrowed += owned.borrow().value
                    }
                },
                PortRef.generate(),
            ),
        )
        membrane.exposedOutlet.observe(PortRef.generate()) { observedEmissions++ }
        membrane.exposedOutlet.subscribe(
            Use.fixed(
                object : ExclusiveFeed {
                    override fun send(owned: Owned<String>) {
                        taken += owned.take()
                    }
                },
                PortRef.generate(),
            ),
        )

        val payload = Owned("one")
        membrane.exposedOutlet.call.send(payload)
        controller.runToIdle()

        // ONE invocation of the transform for that emission, with all three
        // attached — this is the assertion that fails (3) before the fix.
        borrowCountingProjectionCalls.get() shouldBe 1
        // and every attachment still got its delivery: sharing the verdict is
        // not skipping the fan-out.
        borrowed shouldBe listOf("one")
        taken shouldBe listOf("one")
        observedEmissions shouldBe 1
        // Consumed at most once across the consumer and observer paths: the
        // consumer's take() was it, so the original is now use-after-move.
        assertThrows<IllegalStateException> { payload.take() }

        // A second emission is one further evaluation, not four: the memo is
        // per emission and never leaks across them.
        membrane.exposedOutlet.call.send(Owned("two"))
        controller.runToIdle()
        borrowCountingProjectionCalls.get() shouldBe 2
        taken shouldBe listOf("one", "two")

        // A projected (delivered) emission is no denial at all.
        membrane.boundaryDenials["exposedOutlet"]!!.denialCount shouldBe 0L
    }

    @Test
    fun `BS-6 a suppressed emission discharges its exclusives exactly once while still counting every attempt`() {
        // [SEC1-20] over the D1 evaluation split. Three attachments, one
        // suppressed emission carrying an Owned and a Leased from a counting
        // pool:
        //
        //  - the denial counter still moves ONCE PER ATTEMPT (usd.1's decided
        //    semantics, which D1 preserves through the no-args repeat hook);
        //  - the payload reaches the single spec-23-R8 sanitizer exactly ONCE,
        //    so the lease is released once and the pool's outstanding count
        //    returns to its pre-emission value, and the Owned is frozen once;
        //  - only that first record carries arguments; the repeats carry none,
        //    which is what makes the discharge exactly-once rather than
        //    "twice, but tolerated".
        //
        // Against the unfixed code the *counting* and the pool balance already
        // pass (each attempt re-sanitizes the same wrappers, and the sanitizer
        // swallows the already-consumed throw), but every one of the three
        // records carries arguments — so `valued.size shouldBe 1` reads 3.
        val controller = SimulationController(seed = 22)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)

        val membrane = ExclusiveMembrane()
        host.managementInlet.call.spawn(membrane)
        controller.runToIdle()
        val sink = membrane.boundaryDenials["exposedOutlet"]!!

        var outstandingLeases = 0
        fun lease(value: String): Leased<String> {
            outstandingLeases++
            return Leased(value) { outstandingLeases-- }
        }

        var delivered = 0
        var tapped = 0
        var observedEmissions = 0
        membrane.exposedOutlet.tap(
            Use.fixed(
                object : ExclusiveDrop {
                    override fun send(owned: Owned<String>, leased: Leased<String>) {
                        tapped++
                    }
                },
                PortRef.generate(),
            ),
        )
        membrane.exposedOutlet.observe(PortRef.generate()) { observedEmissions++ }
        membrane.exposedOutlet.subscribe(
            Use.fixed(
                object : ExclusiveDrop {
                    override fun send(owned: Owned<String>, leased: Leased<String>) {
                        delivered++
                    }
                },
                PortRef.generate(),
            ),
        )

        val owned = Owned("owned-secret")
        val leased = lease("leased-secret")
        outstandingLeases shouldBe 1

        membrane.exposedOutlet.call.send(owned, leased)
        controller.runToIdle()

        // Suppressed on every path — including the observer, which [SEC1-19]
        // does not exempt: it constrains the evaluation count, not the gate.
        delivered shouldBe 0
        tapped shouldBe 0
        observedEmissions shouldBe 0

        // Per-attempt accounting, unweakened: three attachments, three records.
        sink.denialCount shouldBe 3L
        letters.size shouldBe 3

        // Discharged exactly once. The pool is back where it started, and both
        // wrappers refuse a second discharge — which is also the proof the
        // repeats did not quietly re-run it (a second release would have thrown
        // inside the sanitizer's runCatching and gone unseen; the pool count
        // would not).
        outstandingLeases shouldBe 0
        assertThrows<IllegalStateException> { leased.release() }
        assertThrows<IllegalStateException> { owned.take() }

        // Exactly one record carried the payload to sanitization; the repeats
        // carry no arguments at all.
        val valued = letters.filter { it.invocation!!.invocation.args.isNotEmpty() }
        valued.size shouldBe 1
        val captured = valued.single().invocation!!.invocation.args
        captured.size shouldBe 2
        captured[0].shouldBeInstanceOf<Frozen<*>>().value shouldBe "owned-secret"
        captured[1].shouldBeInstanceOf<Redacted>()
        captured.none { it is Owned<*> || it is Leased<*> } shouldBe true
        // and all three are the same denial, so an auditor counting refusals
        // reads three refusals rather than one refusal plus two other things.
        letters.forEach { it.description shouldContain "DISCLOSURE_DENIED" }
        // Stronger: the repeat records must be INDISTINGUISHABLE from the first
        // one in everything a consumer of denial records classifies on — seam,
        // exposure, principal, subject, reason — so that "N suppressed
        // deliveries reports N" cannot decay into "reports 1 plus two other
        // things" if asDeltaFilter and asRepeatSuppressionHook ever drift. The
        // rendered header is exactly those fields; `detail` (the free-text tail
        // after it, which deliberately says "further suppressed attempt") and
        // the empty args are the only permitted differences.
        letters.map { it.description.substringBefore(" (") }.distinct().size shouldBe 1
        letters.map { it.invocation!!.portName }.distinct() shouldBe listOf("exposedOutlet")
        letters.map { it.invocation!!.invocation.methodName }.distinct() shouldBe listOf("ExclusiveDrop")
        letters.count { it.description.contains("further suppressed attempt") } shouldBe 2
        // and the one record WITHOUT that tail is exactly the one that carried
        // the payload — a reader can always tell which record holds it.
        letters.single { !it.description.contains("further suppressed attempt") } shouldBe valued.single()
    }

    @Test
    fun `seam 2, a mediateOutlet refusal is accounted through that exposure's denial sink`() {
        // Pins the seam where computenet-usd.5.1 (producer-side linkAuthority
        // on a mediateOutlet exposure) meets computenet-usd.1.5 (installLink-
        // Authority wraps each policy so a Rejected verdict is accounted).
        // Neither side's own tests cover the combination: the four `seam 2,
        // ...` tests above assert only the REFUSAL, and BS-2 asserts accounting
        // only for a `mediate` inlet exposure. Measured while reviewing the
        // merge that joined them: installing the outlet's policies WITHOUT the
        // accounting wrapper leaves all four seam-2 tests and all five BS-*
        // tests green while denialCount stays 0 — so this is the only assertion
        // standing between the outlet path and silent under-accounting
        // ([SEC1-25]/[SEC1-26]).
        val controller = SimulationController(seed = 16)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)

        val membrane = SubscribeAuthorityMembrane(allowed = PeerId("alice"))
        val membraneRef = host.managementInlet.call.spawn(membrane)
        val collector = DeltaCollector()
        val collectorRef = host.managementInlet.call.spawn(collector)
        controller.runToIdle()

        val sink = membrane.boundaryDenials["exposedOutlet"]!!
        sink.denialCount shouldBe 0L

        CurrentPeer.with(PeerId("mallory")) {
            host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
        }.shouldBeInstanceOf<LinkResult.Rejected>()
        controller.runToIdle()

        sink.denialCount shouldBe 1L
        letters.size shouldBe 1
        letters.single().description shouldContain "mallory"
        letters.single().description shouldContain "LINK_REFUSED"

        CurrentPeer.with(PeerId("alice")) {
            host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
        }.shouldBeInstanceOf<LinkResult.Connected>()
        controller.runToIdle()
        sink.denialCount shouldBe 1L
    }
}
