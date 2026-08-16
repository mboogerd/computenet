package civictech.cell.membrane

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.data.SetCell
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.CoalescingCombineCell
import civictech.cell.host.DeadLetter
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.SupervisionPolicy
import civictech.cell.link.LinkResult
import civictech.cell.link.PeerId
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkTo
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `computenet-usd.8` — **which [Principal] a fan-out released by one peer's
 * `Progress` ack carries at a mediated outlet**, decided (2026-08-16) as
 * option (a): `LocalTrusted`, with the scope reset at the fan-out.
 *
 * ## The leak this pins shut
 *
 * `computenet-usd.4.3` made `ManagedHost`'s `PORT_PROTOCOL` branch run its
 * delivery under `CurrentPeer.with(hostedInvocation.peer)`, which is required
 * for the seam-3 clamp on a remotely asserted `Attention`
 * (`civictech.cell.wire.BridgeBoundaryPolicyTest`). [civictech.cell.link.CurrentPeer]
 * is a bare `ThreadLocal`, so *everything the delivery synchronously causes*
 * inherited it — including an emission the frame merely **unblocks**. A
 * [Progress] absorb-ack completing a wave in [CoalescingCombineCell] (or
 * `WaveGate`) runs `flushReady()` -> `outlet.call.propagate(...)` on the
 * delivering thread, so a mediated outlet's `disclosureFilter` and its
 * `BoundaryDenialSink` record evaluated under the **acking** peer, for a delta
 * addressed to every attached observer rather than to that peer.
 *
 * ## Why `LocalTrusted` is the honest answer, and what it does not claim
 *
 * The acking peer is the *upstream producer* of one arm — neither a requester
 * nor a recipient of the fan-out. A broadcast is not one peer-scoped crossing:
 * each attachment is a `PortRef` from which "no peer identity is derivable at
 * this seam" ([CompositeCell]'s `denyDisclosure`), and any attachment that is
 * itself remote gets its own bridge crossing, with its own stamped peer, one
 * hop further down. So the fan-out has no single rightful principal, and
 * `LocalTrusted` — the "no peer in scope" reading the denial record already
 * documents — is what it carries.
 *
 * This settles the **scope** question only. Whether a disclosure decision could
 * ever be *per-recipient* is 93 I-28 §8 cross-hop composition, still open and
 * explicitly outside SEC1: `[SEC1-19]` computes one verdict per emission and
 * shares it with every attachment, and `[SEC1-20]` discharges a refused
 * exclusive exactly once against that single evaluation. Both are unchanged
 * here — this is a scope reset around the fan-out, not a per-recipient
 * redesign. See `concord/corpus/DISPUTES.md`.
 *
 * ## What the unicast half fixes in place
 *
 * The reset is at the fan-out ([FanOutlet]'s broadcast `call`), **not** at the
 * `ManagedHost` delivery frame, precisely so the other shape keeps its peer: a
 * pull/catch-up reply through `FanOutlet.at` is genuinely addressed to the
 * asking peer, and still records `Peer` (scenario 2). Narrowing the host frame
 * would have taken both.
 */
class PeerUnblockedFanOutPrincipalTest {

    /** An upstream arm: an outlet only, driven by hand so the test owns the wave position. */
    private class Arm(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<CounterDelta>>())
    }

    /** Whatever the gated exposure would disclose, if it disclosed anything. */
    private class DeltaCollector(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = CopyOnWriteArrayList<CounterDelta>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())

        init {
            inlet.serve(object : Propagate<CounterDelta> {
                override fun propagate(value: CounterDelta) {
                    received += value
                }
            })
        }
    }

    /**
     * The membrane under test: a **gated organelle** behind a `Deny` exposure,
     * plus a plain [SetCell] behind a second one for the unicast control.
     *
     * `gateInlet` is [flatten]ed rather than [mediate]d on purpose — flatten
     * re-registers the organelle's *own* port object, so a `PORT_PROTOCOL`
     * frame addressed to `gateInlet` reaches [CoalescingCombineCell]'s own
     * `Protocols.Progress` handler, which is the whole point of the fixture.
     *
     * Exposure names equal the property names (G-17).
     */
    private class GatedMembrane(
        val gate: CoalescingCombineCell = CoalescingCombineCell(),
        val pulled: SetCell<String> = SetCell(),
    ) : CompositeCell() {
        val gateInlet = flatten("gateInlet", "inlet", gate.inlet)

        val gatedOutlet = mediateOutlet(
            "gatedOutlet",
            "outlet",
            gate.outlet,
            policy = BoundaryPolicy(disclosure = DisclosurePolicy.Deny),
        )

        val pulledOutlet = mediateOutlet(
            "pulledOutlet",
            "outlet",
            pulled.outlet,
            policy = BoundaryPolicy(disclosure = DisclosurePolicy.Deny),
        )
    }

    private interface DeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    private class Run(seed: Long = 0) {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)

        val deadLetters = CopyOnWriteArrayList<DeadLetter>()

        val membrane = GatedMembrane()
        val membraneRef: CellRef

        init {
            host.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
                override fun propagate(value: DeadLetter) {
                    deadLetters += value
                }
            }, PortRef.generate()))
            membraneRef = host.managementInlet.call.spawn(membrane)
            // A denial is not a fault (`[SEC1-29]`): RESTART is installed so a
            // reclassification would be observable rather than silent.
            host.managementInlet.call.supervise(membraneRef, SupervisionPolicy.RESTART)
            controller.runToIdle()
        }

        /** Every boundary-denial record reported for [exposure], in order. */
        fun denials(exposure: String): List<civictech.cell.BoundaryDenial> =
            deadLetters.mapNotNull { it.denial }.filter { it.exposure == exposure }
    }

    @Suppress("UNCHECKED_CAST")
    private fun link(from: FanOutlet<Propagate<CounterDelta>>, into: FanInlet<Propagate<CounterDelta>>) {
        (from.linkTo(into as LinkFrom<Propagate<CounterDelta>>) is LinkResult.Connected).shouldBeTrue()
    }

    /**
     * Scenario 1 — the decided rule. Arm `a` contributes to wave `(s, 1)`; the
     * gate holds it because arm `b`'s edge has not settled. Peer `q`'s
     * `Progress` ack for `b` completes the wave *inside* the peer-stamped
     * delivery frame, and the coalesced emission fans out to the collector
     * through the `Deny` exposure.
     *
     * Before the fix the denial record read `principal = q`: an audit trail
     * attributing a broadcast's refusal to a peer who was neither its
     * requester nor its recipient.
     */
    @Test
    fun `a fan-out unblocked by a peer's Progress ack is denied as LocalTrusted, not as the acking peer`() {
        val run = Run(seed = 1)
        val armA = Arm()
        val armB = Arm()
        val collector = DeltaCollector()

        link(armA.outlet, run.membrane.gate.inlet)
        link(armB.outlet, run.membrane.gate.inlet)
        // A mediated outlet evaluates its filter per attachment, so the fan-out
        // needs a real one: with nothing attached there is no delivery attempt
        // and no verdict at all.
        link(run.membrane.gatedOutlet, collector.inlet)
        run.controller.runToIdle()

        val source = UUID.randomUUID()
        val wave = Timestamp(source, 1)
        CurrentContext.with(MessageContext(wave, armA.outlet.ref)) {
            armA.outlet.call.propagate(CounterDelta(5))
        }
        run.controller.runToIdle()
        // Held: arm b has not settled this wave, so nothing has crossed yet.
        run.denials("gatedOutlet").shouldContainExactly()
        collector.received.shouldContainExactly()

        val edgeB = run.membrane.gate.inlet.linking.links.first { it.from == armB.outlet.ref }
        // Exactly the shape `BridgeIngressCell` hands `ManagedHost`: a
        // `PORT_PROTOCOL` frame carrying the peer stamp the ingress applies
        // (`decoded.copy(peer = peer)`), delivered through the host's own
        // registry sink. `BridgeBoundaryPolicyTest` drives its link-request
        // seam the same way, and for the same reason — the stamp is what is
        // under test, not the codec.
        run.registry.deliver(
            HostedPortInvocation(
                cellRef = run.membraneRef,
                portName = "gateInlet",
                type = HostedPortInvocation.Type.PORT_PROTOCOL,
                invocation = Invocation("", emptyList(), emptyList()),
                protocolId = Protocols.Progress,
                protocolLink = edgeB,
                protocolMessage = Progress(source, 1),
                peer = PeerId("q"),
            )
        )
        run.controller.runToIdle()

        // The ack really did release the wave — otherwise the assertion below
        // would pass on a fan-out that never happened.
        val denials = run.denials("gatedOutlet")
        denials.size shouldBe 1
        denials.single().reason shouldBe civictech.cell.DenialReason.DISCLOSURE_DENIED
        // The decision: the released fan-out is not the acking peer's crossing.
        denials.single().principal shouldBe null
        // Deny discloses nothing, unblocked or not.
        collector.received.shouldContainExactly()
        // `[SEC1-29]`: a denial is never a fault.
        run.host.supervisionAccounting().restarts shouldBe 0L
    }

    /**
     * Scenario 2 — the control the reset must not take. A pull/catch-up reply
     * travels [FanOutlet.at], is addressed to exactly the peer that asked, and
     * still records `Peer(q)`. This is the half that would have been lost by
     * narrowing `ManagedHost`'s delivery frame instead.
     */
    @Test
    fun `a unicast catch-up reply to the asking peer is still denied as that Peer`() {
        val run = Run(seed = 2)
        // Pre-link state, so the on-link catch-up unicast has something to
        // disclose — and therefore something to refuse.
        run.membrane.pulled.inlet.call.add("one")
        run.controller.runToIdle()

        val collector = DeltaCollector()
        val endpoint = FanInlet.create<Propagate<SetDelta<String>>>()
        endpoint.serve(object : Propagate<SetDelta<String>> {
            override fun propagate(value: SetDelta<String>) = Unit
        })
        run.registry.deliver(
            HostedPortInvocation(
                cellRef = run.membraneRef,
                portName = "pulledOutlet",
                type = HostedPortInvocation.Type.PORT_MANAGEMENT,
                invocation = Invocation.of(LINK_TO, arrayOf(endpoint)),
                peer = PeerId("q"),
            )
        )
        run.controller.runToIdle()

        val denials = run.denials("pulledOutlet")
        denials.size shouldBe 1
        denials.single().principal shouldBe PeerId("q")
        collector.received.shouldContainExactly()
    }

    private companion object {
        /** `LinkTo.linkTo(LinkFrom)` — the handshake-running overload, not the ad-hoc `Use` one. */
        val LINK_TO = LinkTo::class.java.methods.first {
            it.name == "linkTo" && it.parameterTypes.singleOrNull() == LinkFrom::class.java
        }
    }
}
