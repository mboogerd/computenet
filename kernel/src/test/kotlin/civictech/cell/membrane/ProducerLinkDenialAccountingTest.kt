package civictech.cell.membrane

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Propagate
import civictech.cell.control.AttentionBand
import civictech.cell.evolve.Promotion
import civictech.cell.host.DeadLetter
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.SupervisionPolicy
import civictech.cell.link.CurrentPeer
import civictech.cell.link.LinkResult
import civictech.cell.link.PeerId
import civictech.cell.link.allowPeers
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.Protocols
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/** The organelle feed a subscriber attaches to. */
private class PldaFeed(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val outlet = registerPort("outlet", FanOutlet.create<Consumer<String>>())
    fun emit(value: String) = outlet.call.provide(value)
}

/**
 * A membrane exposing its organelle feed under producer-side subscribe
 * authority ([SEC1-12], landed by computenet-usd.5.1). [mediateOutlet] demands
 * a flow-time predicate, so `linkAuthority` rides alongside the cheapest one
 * that changes nothing these tests observe (an attention ceiling on a
 * protocol nobody sends) — same composition as `BoundaryPolicyTest`'s
 * `SubscribeAuthorityMembrane` and `PromotionReauthorizationTest`'s
 * `ExposingMembrane` (each file needs its own copy: Kotlin top-level
 * declarations share the package namespace even when `private`).
 */
private class PldaSubscribeAuthorityMembrane(
    allowed: PeerId,
    ref: CellRef = CellRef(UUID.randomUUID()),
) : CompositeCell(ref) {
    private val organelle = PldaFeed()

    val exposedOutlet: FanOutlet<Consumer<String>> = mediateOutlet(
        "exposedOutlet",
        "outlet",
        organelle.outlet,
        policy = BoundaryPolicy(
            linkAuthority = listOf(allowPeers(allowed)),
            protocolAuthority = mapOf(Protocols.Attention to ProtocolAuthority(ceiling = AttentionBand.LOW)),
        ),
    )

    fun emit(value: String) = organelle.emit(value)
}

private class PldaCollector(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val received = mutableListOf<String>()
    val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

    init {
        inlet.serve(object : Consumer<String> {
            override fun provide(input: String) {
                received += input
            }
        })
    }
}

/** Collects everything the host dead-letters, so a denial's report is readable in-test. */
private fun collectPldaDeadLetters(host: ManagedHost): MutableList<DeadLetter> {
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

/**
 * computenet-usd.5.4 — the producer-side ([SEC1-12], `.5.1`) and PRECHECK
 * re-authorization (`[SEC1-10]`, `.5.2`) `linkAuthority` refusals ride the
 * SEC1 denial-accounting seam (`computenet-usd.1`; `civictech.cell.BoundaryDenials`).
 *
 * ## Both are already accounted with ZERO new production wiring
 *
 * `CompositeCell.installLinkAuthority` (landed by `computenet-usd.1.5`) wraps
 * every `LinkPolicy` an exposure declares BEFORE adding it to the exposed
 * port's own `LinkSupport.policies` — the wrapper accounts any non-null
 * `Rejected` verdict through that exposure's `BoundaryDenialSink` and then
 * returns the verdict unchanged. Both refusal paths this task was asked to
 * check evaluate that SAME wrapped list, not a copy of it:
 *
 * - the producer-side refusal (`civictech.cell.link.handshake`'s
 *   `sourceLinking?.reject(request)`, `.5.1`) calls `LinkSupport.reject`,
 *   which walks `policies.firstNotNullOfOrNull { it.evaluate(request) }` —
 *   the wrapped policies `mediateOutlet` installed on the organelle outlet;
 * - the PRECHECK re-authorization refusal
 *   (`civictech.cell.evolve.Promotion.reauthorizeRebinds`, `.5.2`) calls
 *   `LinkSupport.reauthorize`, which is `= reject(request)` verbatim over the
 *   CANDIDATE outlet's own `linking.policies` — the very list its own
 *   `mediateOutlet` wrapped.
 *
 * So accounting rides through both call sites for free: nothing in
 * `Handshake.kt`, `LinkSupport.kt`, or `Evolution.kt` had to change to make
 * either denial produce a record, a counter increment, and a sanitized dead
 * letter. What this task adds is the decision record (KDoc at
 * `Promotion.reauthorizeRebinds`) and these named assertions proving it,
 * per acceptance: "producer-side link refusal accounted with a named kernel
 * test" and "[the PRECHECK refusal] mirroring exactly what usd.1 did for
 * inlet-side link denials".
 *
 * ## The discharge leg is vacuous here — deliberately untested
 *
 * `civictech.cell.BoundaryDenialSink.deny`'s `deniedArgs` exist to carry a
 * refused crossing's exclusives (`Owned`/`Leased`) through the host's
 * spec-23-R8 sanitizer (see `BoundaryDenialAccountingTest`, which exercises
 * exactly that with a `MediateProxy`/flow-time refusal). A seam-2
 * `civictech.cell.link.LinkRequest` carries no payload at all — only
 * `(from, to, identity, role)` — so there is nothing to discharge at link
 * time and `deny(...)` below is called with no `deniedArgs`. This class does
 * not assert exactly-once discharge for that reason: there is no exclusive in
 * flight to discharge, and asserting it anyway would test nothing.
 */
class ProducerLinkDenialAccountingTest {

    @Test
    fun `producer-side subscribe refusal lands exactly one denial record, one counter increment, a sanitized dead letter, and no RESTART`() {
        val controller = SimulationController(seed = 71)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectPldaDeadLetters(host)

        val membrane = PldaSubscribeAuthorityMembrane(allowed = PeerId("alice"))
        val membraneRef = host.managementInlet.call.spawn(membrane)
        // RESTART, so BS-14 ("a denial is not a fault") is a real check rather
        // than a vacuous one: were this denial misclassified as a cell
        // failure anywhere on this path, this is the policy that would fire.
        host.managementInlet.call.supervise(membraneRef, SupervisionPolicy.RESTART)
        val collector = PldaCollector()
        val collectorRef = host.managementInlet.call.spawn(collector)
        controller.runToIdle()

        val sink = membrane.boundaryDenials["exposedOutlet"]!!
        sink.denialCount shouldBe 0L

        val rejected = CurrentPeer.with(PeerId("mallory")) {
            host.managementInlet.call.connect(membraneRef, "exposedOutlet", collectorRef, "inlet")
        }
        controller.runToIdle()
        rejected.shouldBeInstanceOf<LinkResult.Rejected>()
        rejected.reason shouldContain "mallory"
        rejected.reason shouldContain "allowlist"

        // Exactly one denial record, naming mallory and the refusing policy
        // ([SEC1-09] pattern: the accounting reflects the SAME verdict the
        // handshake already returned, not a re-derivation of it).
        sink.denialCount shouldBe 1L
        letters.size shouldBe 1
        val letter = letters.single()
        letter.cause shouldBe null
        letter.description shouldContain "exposedOutlet"
        letter.description shouldContain "mallory"
        letter.description shouldContain "LINK_REFUSED"
        letter.description shouldContain "not on the allowlist"

        // No subscriber entry remains: the refused request never reached
        // install()/register() ([SEC1-09] pattern).
        membrane.exposedOutlet.linking.links shouldBe emptyList()
        membrane.emit("after-the-refusal")
        controller.runToIdle()
        collector.received shouldBe emptyList()

        // BS-14 / [SEC1-29]: not a fault — no RESTART, no supervision
        // escalation, no wave minted or advanced (the report carries a null
        // MessageContext).
        host.supervisionAccounting().restarts shouldBe 0L
        letter.invocation!!.invocation.context shouldBe null
    }

    @Test
    fun `PRECHECK re-authorization refusal also rides the accounting seam, via the candidate's own wrapped linkAuthority policies`() {
        val controller = SimulationController(seed = 72)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectPldaDeadLetters(host)

        val logicalId = UUID.randomUUID()
        val gate = TrafficLightCell.create<Consumer<String>>()
        val incumbent = PldaSubscribeAuthorityMembrane(PeerId("alice"), CellRef(logicalId, instanceId = 0))
        val candidate = PldaSubscribeAuthorityMembrane(PeerId("bob"), CellRef(logicalId, instanceId = 1))
        val collector = PldaCollector()

        host.managementInlet.call.spawn(gate)
        val incumbentRef = host.managementInlet.call.spawn(incumbent)
        host.managementInlet.call.spawn(candidate)
        val collectorRef = host.managementInlet.call.spawn(collector)
        controller.runToIdle()

        CurrentPeer.with(PeerId("alice")) {
            host.managementInlet.call.connect(incumbentRef, "exposedOutlet", collectorRef, "inlet")
        }.shouldBeInstanceOf<LinkResult.Connected>()
        controller.runToIdle()

        val candidateSink = candidate.boundaryDenials["exposedOutlet"]!!
        candidateSink.denialCount shouldBe 0L
        letters.size shouldBe 0

        val aborted = shouldThrow<Promotion.PromotionAborted> {
            Promotion.promote(
                host,
                gate,
                incumbent,
                candidate,
                "exposedOutlet",
                downstream = listOf(collector.inlet),
            )
        }
        controller.runToIdle()
        aborted.message!! shouldContain "PRECHECK"
        aborted.message!! shouldContain "alice"
        aborted.message!! shouldContain "allowlist"

        // This task's decision (recorded at Promotion.reauthorizeRebinds): the
        // re-authorization refusal is NOT a second, parallel accounting
        // mechanism — it rides the exact same wrapped-policy path a fresh
        // handshake would, because `reauthorize` IS `reject` over the
        // candidate outlet's own `linking.policies`, the list its
        // `mediateOutlet` wrapped. So this needed no new wiring, only this
        // assertion and the KDoc record.
        candidateSink.denialCount shouldBe 1L
        letters.size shouldBe 1
        val letter = letters.single()
        letter.cause shouldBe null
        letter.description shouldContain "exposedOutlet"
        letter.description shouldContain "alice"
        letter.description shouldContain "LINK_REFUSED"
        letter.description shouldContain "not on the allowlist"

        // [SEC1-09] pattern: the incumbent's link survives untouched, and
        // [SEC1-29]: PRECHECK is pure evaluation — no supervision touched.
        incumbent.exposedOutlet.linking.links.size shouldBe 1
        host.supervisionAccounting().restarts shouldBe 0L
    }
}
