package civictech.inspect.edit

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.evolve.ObservationWindow
import civictech.cell.evolve.Promotion
import civictech.cell.evolve.PromotionPolicy
import civictech.cell.evolve.StateMigrating
import civictech.cell.graph.BoundaryLink
import civictech.cell.graph.CellFactory
import civictech.cell.graph.Direction
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkResult
import civictech.cell.membrane.CompositeCell
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import civictech.inspect.InspectorServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.File
import java.io.Serializable
import java.util.UUID

/** WKB2 F9 single-instance promotion through the staged write plane. */
class PromotionCutOverTest {
    private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)

    private class SourceCell(
        clazz: Class<Consumer<Int>>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val outlet = registerPort("outlet", FanOutlet(clazz))
        fun emit(value: Int) = outlet.call.provide(value)
    }

    private open class SummerV1(override val ref: CellRef) : Cell, Stateful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Long>>())
        private var sum = 0L

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    sum += input
                    outlet.call.provide(sum)
                }
            })
        }

        override fun snapshot(): Serializable = sum
        override fun restore(state: Serializable) {
            sum = state as Long
        }
    }

    private open class SummerV2(override val ref: CellRef) : Cell, StateMigrating {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Long>>())
        private var sum = 0L

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    sum += input
                    outlet.call.provide(sum)
                }
            })
        }

        override fun importFrom(prior: Serializable) {
            sum = prior as Long
        }
    }

    private class FailingSummerV2(ref: CellRef) : SummerV2(ref) {
        override fun importFrom(prior: Serializable) {
            throw IllegalStateException("candidate state transfer boom")
        }
    }

    private class NonIdempotentSummerV2(override val ref: CellRef) : Cell, Promotion.NonIdempotentCatchUp {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Long>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) = outlet.call.provide(input.toLong())
            })
        }
    }

    private class DifferentContractCandidate(override val ref: CellRef) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())
    }

    private class CollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Long>()
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Long>>())

        init {
            inlet.serve(object : Consumer<Long> {
                override fun provide(input: Long) {
                    received += input
                }
            })
        }
    }

    /** Minimal coupled-flow witness: an organelle's inlet/outlet are flattened onto the composite. */
    private class CompositeSummer(ref: CellRef) : CompositeCell(ref) {
        private val inner = SummerV1(CellRef(UUID.randomUUID()))
        val inlet = flatten("inlet", "inlet", inner.inlet)
        val outlet = flatten("outlet", "outlet", inner.outlet)
    }

    private inner class Fixture(
        seed: Long,
        incumbentFactory: (CellRef) -> Cell = ::SummerV1,
    ) {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val logicalId = UUID.randomUUID()
        val source = SourceCell(consumerInt)
        val gate = TrafficLightCell.create<Consumer<Int>>()
        val incumbent = incumbentFactory(CellRef(logicalId, instanceId = 0))
        val collector = CollectorCell()
        private var now = 1_000L

        init {
            listOf(source, gate, incumbent, collector).forEach(host.managementInlet.call::spawn)
            controller.runToIdle()
            connect(source.ref, "outlet", gate.ref, "dataInlet")
            connect(gate.ref, "dataOutlet", incumbent.ref, "inlet")
            connect(incumbent.ref, "outlet", collector.ref, "inlet")
            gate.controlInlet.call.setGreen()
        }

        fun connect(from: CellRef, outlet: String, to: CellRef, inlet: String) {
            host.managementInlet.call.connect(from, outlet, to, inlet).shouldBeInstanceOf<LinkResult.Connected>()
        }

        fun applier(listener: ApplyListener = ApplyListener.None) = StagedApplier(
            hosts = mapOf(HOST to host),
            registry = registry,
            clock = { now++ },
            listener = listener,
        )

        fun request(gateRef: CellRef = gate.ref) = PromotionRequest(
            incumbent = incumbent.ref,
            gate = gateRef,
            candidateHandle = CANDIDATE,
        )

        fun draft(
            factory: CellFactory = CellFactory(::SummerV2),
            identity: IdentityBinding = IdentityBinding.NewInstanceOf(logicalId),
            gateRef: CellRef = gate.ref,
            includeCandidateFeed: Boolean = true,
            request: PromotionRequest = request(gateRef),
        ) = Draft(
            host = HOST,
            spec = GraphSpec(listOf(SpawnStep(CANDIDATE, factory, identity = identity))),
            boundary = if (includeCandidateFeed) {
                listOf(BoundaryLink(gateRef, "dataOutlet", CANDIDATE, "inlet", Direction.INBOUND))
            } else {
                emptyList()
            },
            promotions = listOf(request),
        )

        fun apply(draft: Draft, id: String = "promotion") =
            applier().apply(draft, id, "operator", baseTopologyVersion = 1)

        fun emit(vararg values: Int) {
            values.forEach {
                source.emit(it)
                controller.runToIdle()
            }
        }

        fun promotionStep(plan: PlanDto): PlannedStepDto = plan.steps.single { it.action == "PROMOTE" }
    }

    @Test
    fun `WKB2-22 a committed promotion uses the gated primitive and keeps the downstream stream unbroken`() {
        val f = Fixture(seed = 22)
        f.emit(1, 2, 3)

        val record = f.apply(f.draft())
        f.controller.runToIdle()
        f.emit(4, 5, 6)

        f.collector.received shouldContainExactly listOf(1L, 3L, 6L, 10L, 15L, 21L)
        record.outcome shouldBe ApplyOutcome.Committed
        val promotion = record.promotions.single()
        promotion.status shouldBe PromotionRecord.Status.COMMITTED
        promotion.incumbentRetired shouldBe true
        promotion.reversible shouldBe false
        f.host.portAt(f.incumbent.ref, "outlet").shouldBeNull()
        f.host.cellAt(InspectorServer.decodeRef(promotion.candidate!!).shouldNotBeNull()).shouldNotBeNull()
    }

    @Test
    fun `WKB2-22 PromotionCutOver performs no ad-hoc link surgery`() {
        val source = File("src/main/kotlin/civictech/inspect/edit/PromotionCutOver.kt").readText()

        source shouldContain "Evolve.promoteDirect("
        listOf("setRed", "setGreen", ".subscribe(", ".unsubscribe(", ".despawn(", ".connect(").forEach {
            source shouldNotContain it
        }
    }

    @Test
    fun `B8 WKB2-23 a mid-COMMIT failure reports rollback with the incumbent retained and no compensation`() {
        val f = Fixture(seed = 23)
        f.emit(1, 2, 3)

        val record = f.apply(f.draft(factory = CellFactory(::FailingSummerV2)), id = "rollback")

        val outcome = record.outcome.shouldBeInstanceOf<ApplyOutcome.RolledBackAtCommit>()
        outcome.reason shouldContain "candidate state transfer boom"
        outcome.retained shouldBe InspectorServer.encodeRef(f.incumbent.ref)
        val promotion = record.promotions.single()
        promotion.status shouldBe PromotionRecord.Status.ROLLED_BACK_AT_COMMIT
        promotion.retained shouldBe true
        record.steps.getValue(CANDIDATE) shouldBe StepOutcome.Unwound
        val staged = InspectorServer.decodeRef(promotion.candidate!!).shouldNotBeNull()
        f.host.cellAt(staged).shouldBeNull()

        f.emit(4)
        f.collector.received shouldContainExactly listOf(1L, 3L, 6L, 10L)
    }

    @Test
    fun `WKB2-29 a NonIdempotentCatchUp candidate without T0-T1 is refused verbatim and not retried`() {
        val f = Fixture(seed = 29)
        f.emit(1, 2, 3)

        val record = f.apply(f.draft(factory = CellFactory(::NonIdempotentSummerV2)), id = "non-idempotent")

        record.outcome shouldBe ApplyOutcome.UnwoundClean
        val promotion = record.promotions.single()
        promotion.status shouldBe PromotionRecord.Status.REFUSED_AT_PRECHECK
        promotion.reason.shouldNotBeNull() shouldContain
            "candidate declares NonIdempotentCatchUp with no T0/T1 state transfer available"
        record.steps.getValue(CANDIDATE) shouldBe StepOutcome.Unwound
        f.emit(4)
        f.collector.received shouldContainExactly listOf(1L, 3L, 6L, 10L)
    }

    @Test
    fun `B14 WKB2-27 a CompositeCell incumbent is refused naming G-53 without opening a buffer`() {
        val f = Fixture(seed = 27, incumbentFactory = ::CompositeSummer)
        f.emit(1)
        val applier = f.applier()
        val draft = f.draft()

        val step = f.promotionStep(applier.plan(draft))
        step.refusal.shouldNotBeNull().code shouldBe "COUPLED_FLOW"
        step.refusal.shouldNotBeNull().reason shouldContain "G-53"
        val record = applier.apply(draft, "coupled", "operator", 1)
        record.outcome shouldBe ApplyOutcome.RefusedAtPrecheck

        f.emit(2)
        f.collector.received shouldContainExactly listOf(1L, 3L)
    }

    @Test
    fun `NO_GATE reports distinct non-gate unserved-gate and unfed-candidate reasons`() {
        val f = Fixture(seed = 25)
        val applier = f.applier()

        val nonGate = f.promotionStep(
            applier.plan(f.draft(gateRef = f.collector.ref, request = f.request(f.collector.ref))),
        )
        nonGate.refusal.shouldNotBeNull().code shouldBe "NO_GATE"
        nonGate.refusal.shouldNotBeNull().reason shouldContain "not a live TrafficLightApi"

        val unlinkedGate = TrafficLightCell.create<Consumer<Int>>()
        f.host.managementInlet.call.spawn(unlinkedGate)
        f.controller.runToIdle()
        val unserved = f.promotionStep(
            applier.plan(f.draft(gateRef = unlinkedGate.ref, request = f.request(unlinkedGate.ref))),
        )
        unserved.refusal.shouldNotBeNull().code shouldBe "NO_GATE"
        unserved.refusal.shouldNotBeNull().reason shouldContain "does not serve"

        val unfed = f.promotionStep(applier.plan(f.draft(includeCandidateFeed = false)))
        unfed.refusal.shouldNotBeNull().code shouldBe "NO_GATE"
        unfed.refusal.shouldNotBeNull().reason shouldContain "promoted unfed"
    }

    @Test
    fun `candidate binding and outlet contract are checked structurally`() {
        val f = Fixture(seed = 26)
        val applier = f.applier()

        val wrongBinding = f.promotionStep(applier.plan(f.draft(identity = IdentityBinding.FreshLogical)))
        wrongBinding.refusal.shouldNotBeNull().code shouldBe "CONTRACT_MISMATCH"
        wrongBinding.refusal.shouldNotBeNull().reason shouldContain "new instance"

        val wrongContract = f.promotionStep(applier.plan(f.draft(factory = CellFactory(::DifferentContractCandidate))))
        wrongContract.refusal.shouldNotBeNull().code shouldBe "CONTRACT_MISMATCH"
        wrongContract.refusal.shouldNotBeNull().reason shouldContain "93 I-2"
    }

    @Test
    fun `a multi-instance logical cell is refused ROLLING_ONLY under the single form`() {
        val f = Fixture(seed = 54)
        f.host.managementInlet.call.spawn(SummerV1(CellRef(f.logicalId, instanceId = 99)))
        f.controller.runToIdle()

        val step = f.promotionStep(f.applier().plan(f.draft()))

        step.refusal.shouldNotBeNull().code shouldBe "ROLLING_ONLY"
        step.refusal.shouldNotBeNull().reason shouldContain "rolling form"
    }

    @Test
    fun `WKB2-24 committed is published before incumbent retirement is recorded`() {
        val f = Fixture(seed = 24)
        val done = mutableListOf<ApplyRecord>()
        val listener = object : ApplyListener by ApplyListener.None {
            override fun onDone(record: ApplyRecord) {
                done += record
            }
        }
        val applier = f.applier(listener)

        applier.apply(f.draft(), "ordering", "operator", 1)

        done.single().promotions.single().incumbentRetired shouldBe false
        applier.record("ordering").shouldNotBeNull().promotions.single().incumbentRetired shouldBe true
    }

    @Test
    fun `B9 a committed promotion records that reversal is a fresh swap`() {
        val f = Fixture(seed = 9)

        val promotion = f.apply(f.draft(), id = "irreversible").promotions.single()

        promotion.reversible shouldBe false
        promotion.reversibleNote shouldContain "fresh swap in the reverse direction"
    }

    @Test
    fun `promotion caller faults are refused by plan and apply`() {
        val f = Fixture(seed = 28)
        val applier = f.applier()
        val request = f.request()
        val candidate = SpawnStep(
            CANDIDATE,
            CellFactory(::SummerV2),
            identity = IdentityBinding.NewInstanceOf(f.logicalId),
        )
        val feed = BoundaryLink(f.gate.ref, "dataOutlet", CANDIDATE, "inlet", Direction.INBOUND)

        val two = Draft(HOST, GraphSpec(listOf(candidate)), listOf(feed), promotions = listOf(request, request))
        shouldThrow<IllegalArgumentException> { applier.plan(two) }
        shouldThrow<IllegalArgumentException> { applier.apply(two, "two", "operator", 1) }

        val despawnedIncumbent = Draft(
            HOST,
            GraphSpec(listOf(candidate)),
            listOf(feed),
            despawns = listOf(f.incumbent.ref),
            promotions = listOf(request),
        )
        shouldThrow<IllegalArgumentException> { applier.plan(despawnedIncumbent) }
        shouldThrow<IllegalArgumentException> { applier.apply(despawnedIncumbent, "overlap", "operator", 1) }

        shouldThrow<IllegalArgumentException> { PromotionRequest(incumbent = f.incumbent.ref) }
        shouldThrow<IllegalArgumentException> {
            PromotionRequest(
                incumbent = f.incumbent.ref,
                gate = f.gate.ref,
                candidateHandle = CANDIDATE,
                replicaCandidate = CellFactory(::SummerV2),
            )
        }

        val policyRequest = request.copy(
            policy = PromotionPolicy(emptyList(), ObservationWindow(1), judge = "judge"),
        )
        val policyDraft = f.draft(request = policyRequest)
        shouldThrow<IllegalArgumentException> { applier.plan(policyDraft) }
        shouldThrow<IllegalArgumentException> { applier.apply(policyDraft, "policy", "operator", 1) }

        val rollingRequest = PromotionRequest(
            incumbent = f.incumbent.ref,
            replicaCandidate = CellFactory(::SummerV2),
        )
        val rollingDraft = Draft(HOST, GraphSpec(emptyList()), promotions = listOf(rollingRequest))
        shouldThrow<IllegalArgumentException> { applier.plan(rollingDraft) }
        shouldThrow<IllegalArgumentException> { applier.apply(rollingDraft, "rolling", "operator", 1) }
    }

    private companion object {
        const val HOST = "h"
        const val CANDIDATE = "v2"
    }
}
