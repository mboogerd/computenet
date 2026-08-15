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
 * **Migration is NOT covered here.** No landed cross-host migration driver
 * exercises a mediated exposure (verified 2026-08-15: no test/harness moves a
 * `CompositeCell` across hosts) — inventing one to make this file "complete"
 * would exercise a fixture, not the migration path, so it is not attempted.
 * I-2 states migration shares RESTART's instanceId-preservation rule and
 * therefore the same no-re-authorization consequence; that claim rests on the
 * spec's stated invariant, not on a test in this file, and is recorded here
 * as an untested hypothesis rather than silently assumed covered.
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
