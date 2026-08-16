package civictech.cell.membrane

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Propagate
import civictech.cell.control.AttentionBand
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SupervisionPolicy
import civictech.cell.link.CurrentPeer
import civictech.cell.link.LinkPolicy
import civictech.cell.link.LinkRequest
import civictech.cell.link.LinkResult
import civictech.cell.link.PeerId
import civictech.cell.link.allowPeers
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.Protocols
import civictech.testkit.SimWorld
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/** Poisonable admin control: throws on "poison" so a hosted invocation on it crashes the cell. */
private fun CompositeCell.installPoisonControl(): FanInlet<Consumer<String>> {
    val control = registerPort("control", FanInlet.create<Consumer<String>>())
    control.serve(object : Consumer<String> {
        override fun provide(input: String) {
            if (input == "poison") throw IllegalStateException("poison: $input")
        }
    })
    return control
}

/** Proxy shape for driving [installPoisonControl]'s port through the host (supervision-visible). */
private interface ControlProxy {
    val control: Use<Consumer<String>>
}

/**
 * [SEC1-11] (93 I-28 §4.3 seam 2, I-2): a supervision RESTART preserves
 * `instanceId` and is NOT a rebind — it MUST NOT re-evaluate `linkAuthority`.
 * The complement of computenet-usd.5.2 (promotion, which IS a rebind and MUST
 * re-authorize). Both tests here narrow the installed policy to exclude the
 * already-admitted peer *after* establishment, then RESTART the hosting cell,
 * and assert two things together: the established link survives (traffic
 * still flows) and the counting [LinkPolicy] wrapper's evaluation count is
 * byte-identical to what it was right after establishment — not merely that
 * the graph still works, but that authority was never consulted again.
 *
 * Verified mechanism (read, not assumed, before writing these tests):
 * `ManagedHost`'s RESTART branch (`ManagedHost.kt` ~1004-1030) calls
 * `cell.onDeactivate`/`onActivate`, mints a fresh emission epoch on every
 * `FanOutlet` in the restarted cell's own [civictech.cell.port.PortRegistry]
 * (`FanOutlet.mintFreshEpoch`, which touches only `sourceId`/`waveCounter`),
 * optionally restores a checkpoint and re-baselines — and never touches
 * `Linked.linking` (subscriber/policy state) or calls `handshake`/`reject` on
 * either side. The composite cells under test are neither `Stateful` nor
 * `ReBaselineEmitting`, so RESTART here is exactly `onDeactivate` →
 * (mint fresh epoch) → `onActivate`, nothing else — the narrowest possible
 * form of the mechanism being pinned.
 *
 * **Migration is covered too** (computenet-qs22), by the same method and
 * through the real driver rather than a fixture: `HostManagementApi.migrate`
 * (spec 33 — the host is the unit of mobility, so a mediated exposure moves
 * when its host does) drains the source host and re-spawns every cell on the
 * target. I-2 states migration shares RESTART's instanceId-preservation rule
 * and therefore the same no-re-authorization consequence; the third test here
 * asserts it directly, so that is no longer a hypothesis. What the pin does
 * NOT claim is anything about a *wire-crossing* move: `migrate` is a
 * host-to-host management call, and no JVM boundary is crossed here.
 */
class RestartLinkAuthorityTest {

    /** Records every [evaluate] call and delegates — the pin's instrument, not its subject. */
    private class CountingLinkPolicy(private val delegate: LinkPolicy) : LinkPolicy {
        var evaluations = 0
            private set

        override fun evaluate(request: LinkRequest): LinkResult.Rejected? {
            evaluations++
            return delegate.evaluate(request)
        }
    }

    /** Appended after establishment to narrow the policy: refuses [identity] outright. */
    private fun excluding(identity: PeerId): LinkPolicy = LinkPolicy { request ->
        if (request.identity == identity) {
            LinkResult.Rejected("excluded after establishment (RESTART pin, not a real policy)")
        } else {
            null
        }
    }

    private class DeltaCollector(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<SetDelta<String>>()
        val inlet = registerPort(
            "inlet",
            FanInlet.create<Propagate<SetDelta<String>>>(),
        )

        init {
            inlet.serve(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) {
                    received += value
                }
            })
        }
    }

    /** An external producer whose outlet pushes `SetOps<String>` calls to subscribers. */
    private class SetSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<SetOps<String>>())
    }

    /**
     * Producer side ([SEC1-12] option (a), landed by computenet-usd.5.1): a
     * `mediateOutlet` exposure whose `linkAuthority` gates subscription at the
     * SOURCE side of the handshake.
     */
    private class SubscribeAuthorityMembrane(
        val organelle: SetCell<String> = SetCell(),
        countingPolicy: CountingLinkPolicy,
    ) : CompositeCell() {
        val exposedOutlet = mediateOutlet(
            "exposedOutlet",
            "outlet",
            organelle.outlet,
            policy = BoundaryPolicy(
                linkAuthority = listOf(countingPolicy),
                // mediateOutlet demands a flow-time predicate; an attention
                // ceiling on a protocol nobody sends is the cheapest one that
                // changes nothing these tests observe (BoundaryPolicyTest's
                // own pattern).
                protocolAuthority = mapOf(Protocols.Attention to ProtocolAuthority(ceiling = AttentionBand.LOW)),
            ),
        )
        val control = installPoisonControl()
    }

    /** Inlet side (landed seam): a `mediate()` exposure whose `linkAuthority` gates the target side. */
    private class SubscribeAuthorityInletMembrane(
        val organelle: SetCell<String> = SetCell(),
        countingPolicy: CountingLinkPolicy,
    ) : CompositeCell() {
        val exposedInlet = mediate(
            "exposedInlet",
            "inlet",
            organelle.inlet,
            policy = BoundaryPolicy(linkAuthority = listOf(countingPolicy)),
        )

        // Default-open flatten, purely to observe that traffic still reaches
        // a downstream consumer after the exposedInlet's link survives RESTART.
        val exposedOutlet = flatten("exposedOutlet", "outlet", organelle.outlet)
        val control = installPoisonControl()
    }

    @Test
    fun `producer-side RESTART preserves the subscribe binding without re-evaluating linkAuthority`() {
        val world = SimWorld(seed = 40)
        val controller = world.controller
        val host = world.host

        val alice = PeerId("alice")
        val countingPolicy = CountingLinkPolicy(allowPeers(alice))
        val membrane = SubscribeAuthorityMembrane(countingPolicy = countingPolicy)
        val membraneRef = host.managementInlet.call.spawn(membrane)
        host.managementInlet.call.supervise(membraneRef, SupervisionPolicy.RESTART)

        val collector = DeltaCollector()
        val collectorRef = host.managementInlet.call.spawn(collector)

        CurrentPeer.with(alice) {
            host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
        }.shouldBeInstanceOf<LinkResult.Connected>()
        controller.runToIdle()

        val evaluationsAtEstablishment = countingPolicy.evaluations
        (evaluationsAtEstablishment > 0) shouldBe true

        // Narrow the policy AFTER establishment: alice is now excluded. The
        // list is mutable and lives on the outlet's own LinkSupport (this IS
        // the organelle outlet object, mediateOutlet's flatten-style reuse).
        membrane.exposedOutlet.linking.policies += excluding(alice)

        // Crash the membrane; RESTART preserves its instanceId (CellRef is
        // stable across a RESTART — only the emission epoch mints fresh).
        val control = HostedCellProxy.create(membraneRef, host, ControlProxy::class.java) as ControlProxy
        control.control.call.provide("poison")
        controller.runToIdle()

        host.supervisionAccounting().restarts shouldBe 1L
        host.generationOf(membraneRef) shouldBe 1L

        // The link survives — no rebind, no re-subscription.
        membrane.exposedOutlet.linking.links.size shouldBe 1

        // The narrowed policy was NEVER consulted: evaluation count is
        // byte-identical to establishment, despite now excluding alice.
        countingPolicy.evaluations shouldBe evaluationsAtEstablishment

        // Traffic over the preserved binding still reaches the consumer.
        membrane.organelle.inlet.call.add("post-restart")
        controller.runToIdle()
        collector.received.flatMap { it.adds.keys } shouldBe listOf("post-restart")
    }

    /**
     * computenet-qs22 — the MIGRATION half of [SEC1-11], through the real
     * driver: `HostManagementApi.migrate` (spec 33, the host is the unit of
     * mobility) moves the membrane and its peer cells from one [ManagedHost]
     * to another, drain and all. No fixture stands in for the move.
     */
    @Test
    fun `cross-host migration preserves the subscribe binding without re-evaluating linkAuthority`() {
        val world = SimWorld(seed = 42)
        val controller = world.controller
        val hostA = world.host
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = world.registry)

        val alice = PeerId("alice")
        val countingPolicy = CountingLinkPolicy(allowPeers(alice))
        val membrane = SubscribeAuthorityMembrane(countingPolicy = countingPolicy)
        val membraneRef = hostA.managementInlet.call.spawn(membrane)

        val collector = DeltaCollector()
        val collectorRef = hostA.managementInlet.call.spawn(collector)

        CurrentPeer.with(alice) {
            hostA.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
        }.shouldBeInstanceOf<LinkResult.Connected>()
        controller.runToIdle()

        val evaluationsAtEstablishment = countingPolicy.evaluations
        (evaluationsAtEstablishment > 0) shouldBe true

        // Narrow the policy AFTER establishment, exactly as the RESTART pins do.
        membrane.exposedOutlet.linking.policies += excluding(alice)

        hostA.managementInlet.call.migrate(hostB.managementInlet)
        controller.runToIdle()

        // The move really happened: the registry resolves the membrane on the
        // target host, not the source.
        world.registry.locate(membraneRef) shouldBe hostB

        // No rebind: the established link survives the move...
        membrane.exposedOutlet.linking.links.size shouldBe 1
        // ...and the narrowed policy was never consulted.
        countingPolicy.evaluations shouldBe evaluationsAtEstablishment

        // Traffic over the preserved binding still reaches the consumer.
        membrane.organelle.inlet.call.add("post-migration")
        controller.runToIdle()
        collector.received.flatMap { it.adds.keys } shouldBe listOf("post-migration")

        // Non-vacuity: the counting policy is still installed and still live on
        // the migrated exposure — a NEW subscription by the same peer is
        // evaluated, and refused by the narrowed policy. So "evaluations
        // unchanged" above is authority not being consulted, not an instrument
        // that the move quietly detached.
        val latecomer = DeltaCollector()
        val latecomerRef = hostB.managementInlet.call.spawn(latecomer)
        CurrentPeer.with(alice) {
            hostB.managementInlet.call.connect(membraneRef, "exposedOutlet", latecomerRef, "inlet")
        }.shouldBeInstanceOf<LinkResult.Rejected>()
        (countingPolicy.evaluations > evaluationsAtEstablishment) shouldBe true
    }

    @Test
    fun `inlet-side RESTART preserves the mediate binding without re-evaluating linkAuthority`() {
        val world = SimWorld(seed = 41)
        val controller = world.controller
        val host = world.host

        val alice = PeerId("alice")
        val countingPolicy = CountingLinkPolicy(allowPeers(alice))
        val membrane = SubscribeAuthorityInletMembrane(countingPolicy = countingPolicy)
        val membraneRef = host.managementInlet.call.spawn(membrane)
        host.managementInlet.call.supervise(membraneRef, SupervisionPolicy.RESTART)

        val source = SetSource()
        val sourceRef = host.managementInlet.call.spawn(source)
        val collector = DeltaCollector()
        val collectorRef = host.managementInlet.call.spawn(collector)

        host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
            .shouldBeInstanceOf<LinkResult.Connected>()

        CurrentPeer.with(alice) {
            host.managementInlet.call.connect(sourceRef, "outlet", membraneRef, "exposedInlet")
        }.shouldBeInstanceOf<LinkResult.Connected>()
        controller.runToIdle()

        val evaluationsAtEstablishment = countingPolicy.evaluations
        (evaluationsAtEstablishment > 0) shouldBe true

        // Narrow the policy AFTER establishment (same mutable-list mechanism).
        membrane.exposedInlet.linking.policies += excluding(alice)

        val control = HostedCellProxy.create(membraneRef, host, ControlProxy::class.java) as ControlProxy
        control.control.call.provide("poison")
        controller.runToIdle()

        host.supervisionAccounting().restarts shouldBe 1L
        host.generationOf(membraneRef) shouldBe 1L

        // The upstream link into the membrane's exposed inlet survives.
        membrane.exposedInlet.linking.links.size shouldBe 1

        // Never re-consulted: evaluation count unchanged despite the narrowed
        // policy now excluding alice.
        countingPolicy.evaluations shouldBe evaluationsAtEstablishment

        // Traffic over the preserved binding still flows end to end: source
        // -> exposedInlet -> organelle -> organelle.outlet (flattened) -> collector.
        CurrentPeer.with(alice) {
            source.outlet.call.add("post-restart")
        }
        controller.runToIdle()
        collector.received.flatMap { it.adds.keys } shouldBe listOf("post-restart")
    }
}
