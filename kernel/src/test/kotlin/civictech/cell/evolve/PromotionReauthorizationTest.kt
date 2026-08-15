package civictech.cell.evolve

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.control.AttentionBand
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.CurrentPeer
import civictech.cell.link.LinkResult
import civictech.cell.link.PeerId
import civictech.cell.link.allowPeers
import civictech.cell.membrane.BoundaryPolicy
import civictech.cell.membrane.CompositeCell
import civictech.cell.membrane.ProtocolAuthority
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import civictech.cell.protocol.Protocols
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/** The organelle behind the membrane: the feed an external consumer subscribes to. */
private class Feed(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val outlet = registerPort("outlet", FanOutlet.create<Consumer<String>>())
    fun emit(value: String) = outlet.call.provide(value)
}

/**
 * A membrane exposing its organelle's feed under producer-side subscribe
 * authority ([SEC1-12], landed by computenet-usd.5.1). [mediateOutlet] demands
 * a flow-time predicate, so `linkAuthority` rides alongside the cheapest one
 * that changes nothing these tests observe (an attention ceiling on a protocol
 * nobody sends), exactly as `BoundaryPolicyTest` composes it.
 */
private class ExposingMembrane(
    allowed: PeerId,
    ref: CellRef = CellRef(UUID.randomUUID()),
) : CompositeCell(ref) {
    private val organelle = Feed()

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

private class Collector(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
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

/**
 * [SEC1-10] — "promotion IS a rebind and MUST re-authorize" (spec
 * `40-distribution/43-security.md` §"The three seams" item 2; 93 I-28 §4.3
 * seam 2): a link that crossed a mediated exposure under a `linkAuthority`
 * that admitted its subscriber does NOT survive a promotion once that policy
 * has been narrowed to exclude it. Re-authorization runs at [Promotion.promote]
 * PRECHECK — COMMIT is documented non-vetoing — over the identity the link was
 * *established* with, retained by `LinkSupport` at registration.
 *
 * The complementary rule (RESTART/migration preserving `instanceId` does NOT
 * re-authorize, [SEC1-11]) is its own item and is not asserted here.
 */
class PromotionReauthorizationTest {

    private class World(incumbentAllows: PeerId, candidateAllows: PeerId, seed: Long) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler())
        val logicalId: UUID = UUID.randomUUID()

        val gate = TrafficLightCell.create<Consumer<String>>()
        val incumbent = ExposingMembrane(incumbentAllows, CellRef(logicalId, instanceId = 0))
        val candidate = ExposingMembrane(candidateAllows, CellRef(logicalId, instanceId = 1))
        val collector = Collector()

        val incumbentRef: CellRef
        val collectorRef: CellRef

        init {
            host.managementInlet.call.spawn(gate)
            incumbentRef = host.managementInlet.call.spawn(incumbent)
            host.managementInlet.call.spawn(candidate)
            collectorRef = host.managementInlet.call.spawn(collector)
            controller.runToIdle()
        }

        /** Establishes the downstream link through the handshake, as [peer] (null = a local request). */
        fun connect(peer: PeerId?): LinkResult {
            val doConnect = {
                host.managementInlet.call.connect(incumbentRef, "exposedOutlet", collectorRef, "inlet")
            }
            val result = if (peer == null) doConnect() else CurrentPeer.with(peer) { doConnect() }
            controller.runToIdle()
            return result
        }

        fun promote() = Promotion.promote(
            host, gate, incumbent, candidate, "exposedOutlet",
            downstream = listOf(collector.inlet),
        )
    }

    @Test
    fun `a linkAuthority narrowed after establishment refuses the promotion rebind at PRECHECK`() {
        val world = World(incumbentAllows = PeerId("alice"), candidateAllows = PeerId("bob"), seed = 21)
        world.connect(PeerId("alice")).shouldBeInstanceOf<LinkResult.Connected>()

        world.incumbent.emit("before-promotion")
        world.controller.runToIdle()
        world.collector.received shouldBe listOf("before-promotion")

        val aborted = shouldThrow<Promotion.PromotionAborted> { world.promote() }
        // The refusal is a PRECHECK verdict, not a COMMIT infrastructure fault,
        // and it names the refusing policy (93 I-28 §4.4 rule 1).
        aborted.message!! shouldContain "PRECHECK"
        aborted.message!! shouldContain "alice"
        aborted.message!! shouldContain "allowlist"

        // No partial swap ([SEC1-09] pattern): the incumbent is still linked,
        // still serving, and was never despawned.
        world.incumbent.exposedOutlet.linking.links.size shouldBe 1
        world.incumbent.emit("after-the-refusal")
        world.controller.runToIdle()
        world.collector.received shouldBe listOf("before-promotion", "after-the-refusal")
    }

    @Test
    fun `an unchanged linkAuthority re-authorizes the established identity and the promotion proceeds`() {
        val world = World(incumbentAllows = PeerId("alice"), candidateAllows = PeerId("alice"), seed = 22)
        world.connect(PeerId("alice")).shouldBeInstanceOf<LinkResult.Connected>()

        world.incumbent.emit("before-promotion")
        world.controller.runToIdle()

        world.promote()
        world.controller.runToIdle()

        world.candidate.emit("from-the-candidate")
        world.controller.runToIdle()
        world.collector.received shouldBe listOf("before-promotion", "from-the-candidate")
    }

    @Test
    fun `a local downstream carries no retained identity and is unaffected by an allowPeers narrowing`() {
        // Default-open posture ([SEC1-02], spec 43): a link established without
        // a stamped peer retains no identity, and a null identity passes
        // `allowPeers` at rebind time exactly as it does at link time.
        val world = World(incumbentAllows = PeerId("alice"), candidateAllows = PeerId("bob"), seed = 23)
        world.connect(peer = null).shouldBeInstanceOf<LinkResult.Connected>()

        world.promote()
        world.controller.runToIdle()

        world.candidate.emit("from-the-candidate")
        world.controller.runToIdle()
        world.collector.received shouldBe listOf("from-the-candidate")
    }
}
