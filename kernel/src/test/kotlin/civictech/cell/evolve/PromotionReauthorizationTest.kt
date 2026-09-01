package civictech.cell.evolve

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Owned
import civictech.cell.control.AttentionBand
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.CurrentPeer
import civictech.cell.link.LinkPolicy
import civictech.cell.link.LinkResult
import civictech.cell.link.LinkSupport
import civictech.cell.link.Linked
import civictech.cell.link.PeerId
import civictech.cell.link.KeyId
import civictech.cell.link.allowPeers
import civictech.cell.membrane.BoundaryPolicy
import civictech.cell.membrane.CompositeCell
import civictech.cell.membrane.ProtocolAuthority
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkTo
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
            linkAuthority = listOf(allowPeers(KeyId(allowed.name))),
            protocolAuthority = mapOf(Protocols.Attention to ProtocolAuthority(ceiling = AttentionBand.LOW)),
        ),
    )

    fun emit(value: String) = organelle.emit(value)
}

/**
 * A downstream endpoint that admits PRECHECK and then detonates inside COMMIT
 * — the only lever a test has on [Promotion.promote]'s rollback path *after* at
 * least one downstream has already been rebound (`ShadowPromotionTest`'s
 * mid-COMMIT failure throws from the state transfer, which runs before the
 * rebind loop, so it never reaches `Rebound.reverse`).
 *
 * The arming point is its own [LinkPolicy]: `reauthorizeRebinds` evaluates the
 * policy exactly once per downstream, and every [ref] read of that PRECHECK
 * step happens in the `LinkRequest` argument list strictly before the policy
 * runs. So PRECHECK sees a well-behaved endpoint, and the first [ref] read
 * COMMIT makes (`from.linking.identityFor(use.ref)` inside the rebind) throws.
 * Listed SECOND in `downstream`, so the collector's rebind has already been
 * installed and recorded when it does.
 */
private class CommitTripwire : Use<Consumer<String>>, Linked {
    private val realRef = PortRef.generate()
    private var armed = false

    override val linking = LinkSupport().apply {
        policies += LinkPolicy { armed = true; null }
    }

    override val ref: PortRef
        get() = if (armed) error("tripwire: COMMIT read this downstream's ref") else realRef

    override val call: Consumer<String> = object : Consumer<String> {
        override fun provide(input: String) = Unit
    }

    override fun at(portRef: PortRef): Consumer<String> = call

    override fun linkFrom(portOut: LinkTo<Consumer<String>>): LinkResult =
        error("tripwire is never linked through the handshake")
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

    private class World(
        incumbentAllows: PeerId,
        candidateAllows: PeerId,
        seed: Long,
        /**
         * The policy of a SECOND candidate, promoted over the first
         * ([promoteSuccessor]). Only the two-promotion tests narrow it; every
         * other test leaves it equal to [candidateAllows], where it is inert.
         */
        successorAllows: PeerId = candidateAllows,
    ) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler())
        val logicalId: UUID = UUID.randomUUID()

        val gate = TrafficLightCell.create<Consumer<String>>()
        val incumbent = ExposingMembrane(incumbentAllows, CellRef(logicalId, instanceId = 0))
        val candidate = ExposingMembrane(candidateAllows, CellRef(logicalId, instanceId = 1))
        val successor = ExposingMembrane(successorAllows, CellRef(logicalId, instanceId = 2))
        val collector = Collector()

        val incumbentRef: CellRef
        val collectorRef: CellRef

        init {
            host.managementInlet.call.spawn(gate)
            incumbentRef = host.managementInlet.call.spawn(incumbent)
            host.managementInlet.call.spawn(candidate)
            host.managementInlet.call.spawn(successor)
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

        /** A SECOND promotion over the same downstream, candidate -> successor. */
        fun promoteSuccessor() = Promotion.promote(
            host, gate, candidate, successor, "exposedOutlet",
            downstream = listOf(collector.inlet),
        )

        /**
         * The same promotion with a second downstream that fails inside COMMIT
         * *after* the collector has been rebound — the rollback path.
         */
        fun promoteWithCommitFailure(tripwire: CommitTripwire) = Promotion.promote(
            host, gate, incumbent, candidate, "exposedOutlet",
            downstream = listOf(collector.inlet, tripwire),
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

    /**
     * computenet-usd.5.5 — the rebind COMMIT installs is a REAL link record, on
     * the candidate and no longer on the incumbent. Before this, COMMIT was a
     * bare `unsubscribe`/`subscribe`: the candidate outlet ended up serving a
     * consumer it had no [civictech.cell.link.Link] for, while the incumbent
     * kept the record for a consumer it no longer served (`unsubscribe` drops
     * the consumer entry, not the `LinkSupport` one).
     */
    @Test
    fun `a successful promotion moves the registered link from the incumbent onto the candidate`() {
        val world = World(incumbentAllows = PeerId("alice"), candidateAllows = PeerId("alice"), seed = 24)
        world.connect(PeerId("alice")).shouldBeInstanceOf<LinkResult.Connected>()

        world.promote()
        world.controller.runToIdle()

        // no stale record for a consumer the incumbent no longer serves
        world.incumbent.exposedOutlet.linking.links.map { it.to } shouldBe emptyList()
        // and the candidate holds the real record, carrying the ESTABLISHING identity
        world.candidate.exposedOutlet.linking.links.map { it.to } shouldBe listOf(world.collector.inlet.ref)
        world.candidate.exposedOutlet.linking.links.single().from shouldBe world.candidate.exposedOutlet.ref
        world.candidate.exposedOutlet.linking.identityFor(world.collector.inlet.ref) shouldBe PeerId("alice")
    }

    /**
     * computenet-usd.5.5 — the establishing identity survives a promotion, so
     * the SECOND promotion re-authorizes `alice` and not a local request. The
     * narrowing is applied only at the second candidate: the first promotion
     * must succeed, and it is the rebind IT installs that has to carry the
     * identity forward for the second one to have anything to refuse.
     */
    @Test
    fun `an identity retained across one promotion still refuses the next promotion at PRECHECK`() {
        val world = World(
            incumbentAllows = PeerId("alice"),
            candidateAllows = PeerId("alice"),
            seed = 25,
            successorAllows = PeerId("bob"),
        )
        world.connect(PeerId("alice")).shouldBeInstanceOf<LinkResult.Connected>()

        world.promote()
        world.controller.runToIdle()
        world.candidate.emit("from-the-candidate")
        world.controller.runToIdle()
        world.collector.received shouldBe listOf("from-the-candidate")

        val aborted = shouldThrow<Promotion.PromotionAborted> { world.promoteSuccessor() }
        aborted.message!! shouldContain "PRECHECK"
        aborted.message!! shouldContain "alice"
        aborted.message!! shouldContain "allowlist"

        // no partial swap: the first candidate is still linked and still serving
        world.candidate.exposedOutlet.linking.links.size shouldBe 1
        world.candidate.emit("after-the-refusal")
        world.controller.runToIdle()
        world.collector.received shouldBe listOf("from-the-candidate", "after-the-refusal")
    }

    /**
     * computenet-usd.5.5 (review) — "same swap, reversed" now has link records
     * to reverse, so a mid-COMMIT rollback has to restore the topology
     * *identically*, `id`s included: `Rebound.reverse` re-registers the very
     * [civictech.cell.link.Link] objects it detached rather than minting fresh
     * ones. A fresh object would leave every id-keyed reader (the establishing
     * identity, `Link.onUnlink` listeners, protocol relay) pointing at a link
     * that no longer exists, and a promotion that aborted would be observable
     * where the KDoc promises it is "exactly as if promotion had never been
     * called".
     *
     * Nothing else exercises [Promotion]'s `relinked.asReversed()` leg:
     * `ShadowPromotionTest`'s mid-COMMIT failure throws from the state
     * transfer, which runs before the rebind loop.
     */
    @Test
    fun `a rollback after a partial COMMIT restores the incumbent's original link objects`() {
        val world = World(incumbentAllows = PeerId("alice"), candidateAllows = PeerId("alice"), seed = 26)
        world.connect(PeerId("alice")).shouldBeInstanceOf<LinkResult.Connected>()

        val original = world.incumbent.exposedOutlet.linking.links.single()
        world.collector.inlet.linking.links.map { it.id } shouldBe listOf(original.id)

        val aborted = shouldThrow<Promotion.PromotionAborted> {
            world.promoteWithCommitFailure(CommitTripwire())
        }
        aborted.message!! shouldContain "COMMIT"
        world.controller.runToIdle()

        // the incumbent holds the SAME link object it held before, on both
        // sides of the edge, with its establishing identity intact
        world.incumbent.exposedOutlet.linking.links.map { it.id } shouldBe listOf(original.id)
        world.incumbent.exposedOutlet.linking.links.single().from shouldBe world.incumbent.exposedOutlet.ref
        world.incumbent.exposedOutlet.linking.identityFor(world.collector.inlet.ref) shouldBe PeerId("alice")
        world.collector.inlet.linking.links.map { it.id } shouldBe listOf(original.id)

        // and the candidate keeps nothing from the abandoned half-swap
        world.candidate.exposedOutlet.linking.links shouldBe emptyList()

        // the incumbent is still the one serving the downstream
        world.incumbent.emit("after-the-rollback")
        world.controller.runToIdle()
        world.collector.received shouldBe listOf("after-the-rollback")
    }

    private class OwnedFeed(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<ShadowOwnedPush>())
    }

    private class OwnedSink(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val taken = mutableListOf<String>()
        val inlet = registerPort("inlet", FanInlet.create<ShadowOwnedPush>())

        init {
            inlet.serve(object : ShadowOwnedPush {
                override fun push(value: Owned<String>) {
                    taken += value.take()
                }
            })
        }
    }

    /**
     * computenet-usd.5.5 (review) — the SPSC check inside `FanOutlet.subscribe`
     * (spec 23) is the one throw reachable from COMMIT's rebind, and it fires
     * *after* the incumbent's records and subscription have been taken down but
     * *before* the [Promotion] `Rebound` reaches the rollback list — so the
     * outer "same swap, reversed" cannot reverse it. The rebind therefore
     * compensates itself, and a promotion refused there leaves the incumbent
     * exactly as it found it: same [civictech.cell.link.Link] object, same
     * establishing identity, still serving.
     *
     * Provoked the only way it is reachable: an exclusive contract
     * ([ShadowOwnedPush]) whose candidate outlet already holds a subscriber.
     */
    @Test
    fun `an SPSC refusal inside COMMIT's rebind leaves the incumbent's link untouched`() {
        val controller = SimulationController(27)
        val host = ManagedHost(scheduler = controller.scheduler())
        val logicalId = UUID.randomUUID()

        val gate = TrafficLightCell.create<ShadowOwnedPush>()
        val incumbent = OwnedFeed(CellRef(logicalId, instanceId = 0))
        val candidate = OwnedFeed(CellRef(logicalId, instanceId = 1))
        val sink = OwnedSink()

        host.managementInlet.call.spawn(gate)
        val incumbentRef = host.managementInlet.call.spawn(incumbent)
        host.managementInlet.call.spawn(candidate)
        val sinkRef = host.managementInlet.call.spawn(sink)
        controller.runToIdle()

        CurrentPeer.with(PeerId("alice")) {
            host.managementInlet.call.connect(incumbentRef, "outlet", sinkRef, "inlet")
        }.shouldBeInstanceOf<LinkResult.Connected>()
        controller.runToIdle()
        val original = incumbent.outlet.linking.links.single()

        // the candidate's exclusive outlet is already spoken for, so COMMIT's
        // `to.subscribe(use)` — and nothing before it — will refuse
        candidate.outlet.subscribe(
            Use.fixed(
                object : ShadowOwnedPush {
                    override fun push(value: Owned<String>) = Unit
                },
                PortRef.generate(),
            ),
        )

        val aborted = shouldThrow<Promotion.PromotionAborted> {
            Promotion.promote(
                host, gate, incumbent, candidate, "outlet",
                downstream = listOf(sink.inlet),
            )
        }
        aborted.message!! shouldContain "COMMIT"
        aborted.message!! shouldContain "SPSC"

        // the incumbent's half of the aborted rebind was put back, object for object
        incumbent.outlet.linking.links.map { it.id } shouldBe listOf(original.id)
        incumbent.outlet.linking.identityFor(sink.inlet.ref) shouldBe PeerId("alice")
        sink.inlet.linking.links.map { it.id } shouldBe listOf(original.id)

        // ... including the subscription itself: the incumbent still delivers
        incumbent.outlet.call.push(Owned("still-mine"))
        controller.runToIdle()
        sink.taken shouldBe listOf("still-mine")
    }
}
