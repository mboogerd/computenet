package civictech.inspect.edit

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.Replicable
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.evolve.Promotion
import civictech.cell.graph.CellFactory
import civictech.cell.graph.GraphSpec
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import civictech.inspect.InspectorServer
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

/** WKB2 F9 rolling promotion through the staged write plane. */
class PromotionReplicaTest {
    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    /** A rolling-form target with the right outlet shape but no replication contract. */
    private class PlainCell(override val ref: CellRef) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())
    }

    /** The replicated-promotion T2 refusal marker, with SetCell's delta contract. */
    private class NonIdempotentReplica(override val ref: CellRef) :
        Cell,
        Replicable<SetDelta<String>>,
        Promotion.NonIdempotentCatchUp {
        override val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())
        override val deltaInlet =
            registerPort("deltaInlet", FanInlet.create<Propagate<SetDelta<String>>>())
    }

    @Test
    fun `WKB2-54 WKB2-55 a same-ref rolling candidate commits through the gated promoteReplica`() {
        val controller = SimulationController(seed = 54)
        val peer = Peer(controller)
        val incumbent = replicatedSet(peer, controller)
        lateinit var candidate: SetCell<String>
        val draft = rollingDraft(
            incumbent.ref,
            CellFactory { ref -> SetCell<String>(ref).also { candidate = it } },
        )

        val record = applier(peer).apply(draft, "rolling-commit", "operator", 1)
        controller.runToIdle()

        record.outcome shouldBe ApplyOutcome.Committed
        val step = promotionStep(record.plan.shouldNotBeNull())
        step.handle shouldBe null
        step.touches shouldBe listOf(InspectorServer.encodeRef(incumbent.ref))
        val promotion = record.promotions.single()
        promotion.form shouldBe PromotionRecord.Form.ROLLING
        promotion.status shouldBe PromotionRecord.Status.COMMITTED
        promotion.candidate shouldBe InspectorServer.encodeRef(incumbent.ref)
        promotion.incumbentRetired shouldBe false
        (peer.host.cellAt(incumbent.ref) === candidate) shouldBe true
        (peer.host.cellAt(incumbent.ref) !== incumbent) shouldBe true

        peer.host.lookup<SetApi<String>>(incumbent.ref).shouldNotBeNull().inlet.call.add("after")
        controller.runToIdle()
        candidate.membership() shouldBe setOf("after")
    }

    @Test
    fun `B15 WKB2-55 a candidate presenting a different ref is refused with the kernel's reason`() {
        val controller = SimulationController(seed = 55)
        val peer = Peer(controller)
        val incumbent = replicatedSet(peer, controller)
        var attempts = 0
        val freshRef = CellRef(incumbent.ref.id, instanceId = 7)
        val draft = rollingDraft(
            incumbent.ref,
            CellFactory {
                attempts += 1
                SetCell<String>(freshRef)
            },
        )

        val record = applier(peer).apply(draft, "fresh-ref", "operator", 1)

        record.outcome shouldBe ApplyOutcome.UnwoundClean
        val promotion = record.promotions.single()
        promotion.status shouldBe PromotionRecord.Status.REFUSED_AT_PRECHECK
        promotion.reason.shouldNotBeNull() shouldContain
            "replicated promotion must reuse the incumbent's CellRef"
        attempts shouldBe 1
        peer.host.cellAt(incumbent.ref) shouldBe incumbent

        peer.host.lookup<SetApi<String>>(incumbent.ref).shouldNotBeNull().inlet.call.add("still-live")
        controller.runToIdle()
        incumbent.membership() shouldBe setOf("still-live")
    }

    @Test
    fun `B15 WKB2-29 a NonIdempotentCatchUp replica candidate is refused with the kernel's reason`() {
        val controller = SimulationController(seed = 29)
        val peer = Peer(controller)
        val incumbent = replicatedSet(peer, controller)
        val draft = rollingDraft(incumbent.ref, CellFactory(::NonIdempotentReplica))

        val record = applier(peer).apply(draft, "non-idempotent", "operator", 1)

        record.outcome shouldBe ApplyOutcome.UnwoundClean
        val promotion = record.promotions.single()
        promotion.status shouldBe PromotionRecord.Status.REFUSED_AT_PRECHECK
        promotion.reason.shouldNotBeNull() shouldContain
            "replicated promotion has no sound T2 (fresh-epoch) fallback"
        peer.host.cellAt(incumbent.ref) shouldBe incumbent
    }

    @Test
    fun `WKB2-54 a replicated incumbent under the single form is refused ROLLING_ONLY`() {
        val controller = SimulationController(seed = 154)
        val peers = List(2) { Peer(controller) }
        Peering.loopback(peers[0].side, peers[1].side)
        val logicalId = UUID.randomUUID()
        val incumbent = SetCell<String>(CellRef(logicalId, 0)).also {
            peers[0].replication.replicate(it, peers[0].host)
        }
        SetCell<String>(CellRef(logicalId, 1)).also {
            peers[1].replication.replicate(it, peers[1].host)
        }
        val gate = TrafficLightCell.create<Propagate<SetDelta<String>>>()
        peers[0].host.managementInlet.call.spawn(gate)
        controller.runToIdle()
        peers[0].registry.replicasOf(logicalId).size shouldBe 2
        val request = PromotionRequest(
            incumbent = incumbent.ref,
            gate = gate.ref,
            candidateHandle = CANDIDATE,
        )
        val draft = Draft(HOST, GraphSpec(emptyList()), promotions = listOf(request))

        val step = promotionStep(applier(peers[0]).plan(draft))

        step.refusal.shouldNotBeNull().code shouldBe "ROLLING_ONLY"
        step.refusal.shouldNotBeNull().reason shouldContain "rolling form"
    }

    @Test
    fun `WKB2-54 rolling form refuses a non-Replicable incumbent and a missing Replication service`() {
        val controller = SimulationController(seed = 254)
        val peer = Peer(controller)
        val plain = PlainCell(CellRef(UUID.randomUUID())).also(peer.host.managementInlet.call::spawn)
        controller.runToIdle()
        val plainStep = promotionStep(
            applier(peer).plan(rollingDraft(plain.ref, CellFactory { ref -> SetCell<String>(ref) })),
        )
        plainStep.refusal.shouldNotBeNull().code shouldBe "ROLLING_ONLY"
        plainStep.refusal.shouldNotBeNull().reason shouldContain "is not Replicable"

        val incumbent = replicatedSet(peer, controller)
        val noServiceStep = promotionStep(
            applier(peer, replication = null).plan(
                rollingDraft(incumbent.ref, CellFactory { ref -> SetCell<String>(ref) }),
            ),
        )
        noServiceStep.refusal.shouldNotBeNull().code shouldBe "ROLLING_ONLY"
        noServiceStep.refusal.shouldNotBeNull().reason shouldContain
            "this inspector was built without a Replication service"
    }

    @Test
    fun `InspectorServer forwards its optional Replication service to rolling planning`() {
        val controller = SimulationController(seed = 355)
        val peer = Peer(controller)
        val incumbent = replicatedSet(peer, controller)

        InspectorServer(
            registry = peer.registry,
            hosts = mapOf(HOST to peer.host),
            port = 0,
            replication = peer.replication,
        ).use { server ->
            server.stagedApplier.plan(
                rollingDraft(incumbent.ref, CellFactory { ref -> SetCell<String>(ref) }),
            ).appliable shouldBe true
        }
    }

    private fun replicatedSet(peer: Peer, controller: SimulationController): SetCell<String> =
        SetCell<String>(CellRef(UUID.randomUUID(), instanceId = 0)).also {
            peer.replication.replicate(it, peer.host)
            controller.runToIdle()
        }

    private fun applier(peer: Peer, replication: Replication? = peer.replication) = StagedApplier(
        hosts = mapOf(HOST to peer.host),
        registry = peer.registry,
        replication = replication,
        clock = { 1_000L },
    )

    private fun rollingDraft(incumbent: CellRef, candidate: CellFactory) = Draft(
        host = HOST,
        spec = GraphSpec(emptyList()),
        promotions = listOf(PromotionRequest(incumbent = incumbent, replicaCandidate = candidate)),
    )

    private fun promotionStep(plan: PlanDto): PlannedStepDto = plan.steps.single { it.action == "PROMOTE" }

    private companion object {
        const val HOST = "h"
        const val CANDIDATE = "candidate"
    }
}
