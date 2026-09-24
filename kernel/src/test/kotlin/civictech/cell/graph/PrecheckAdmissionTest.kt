package civictech.cell.graph

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Owned
import civictech.cell.control.Magnitude
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkPolicy
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.feedbackInlet
import civictech.cell.port.input
import civictech.cell.port.output
import civictech.cell.port.registerPort
import civictech.gen.wire.Contract
import civictech.gen.wire.Key
import civictech.nature.ContractRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.abs

/** An `Owned`-carrying contract: KSP marks its method `exclusive` (spec 23 SPSC). */
@Contract
interface PrecheckOwnedPush {
    fun push(@Key buffer: Owned<String>)
}

/**
 * WKB2 F2 task 2 (computenet-91xzn.2): [GraphSpec.precheck]'s admission
 * dry-run — AT_CAPACITY, OWNERSHIP_VIOLATION, POLICY_DENIAL,
 * CYCLE_WITHOUT_HEAD ([WKB2-12], [13-LINK-05], [13-LINK-06]) and OWNED_INTAKE
 * ([WKB2-26]) — each with the live admission path's own reason string, and
 * none of it touching the apply host ([WKB2-10]).
 */
class PrecheckAdmissionTest {

    private class Fixture(seed: Long) {
        val controller = SimulationController(seed = seed)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val view = HostLiveView(host, registry)

        fun <C : Cell> spawn(cell: C): C = cell.also { host.managementInlet.call.spawn(it) }

        fun connect(from: CellRef, outlet: String, to: CellRef, inlet: String): LinkResult =
            host.managementInlet.call.connect(from, outlet, to, inlet)

        fun footprint() = Triple(host.subtreeCellCount(), registry.all(), registry.localRefs())
    }

    // ---- cells ----

    private class Src(override val ref: CellRef = fresh()) : Cell {
        val outlet by output<Consumer<String>>()
    }

    private class Relay(override val ref: CellRef = fresh()) : Cell {
        val inlet by input<Consumer<String>>()
        val outlet by output<Consumer<String>>()
    }

    private class SingleSink(override val ref: CellRef = fresh()) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>(singleWriter = true))
    }

    private class OwnedSrc(override val ref: CellRef = fresh()) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<PrecheckOwnedPush>())
    }

    private class OwnedSink(override val ref: CellRef = fresh()) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<PrecheckOwnedPush>())
    }

    /** Weak-tier damper payload: a [Magnitude] feedback payload is a damping witness. */
    private data class Delta(val value: Double) : Magnitude {
        override fun size() = abs(value)
    }

    private class DeltaSrc(override val ref: CellRef = fresh()) : Cell {
        val outlet by output<Consumer<Delta>>()
    }

    private class DeltaRelay(override val ref: CellRef = fresh()) : Cell {
        val inlet by input<Consumer<Delta>>()
        val outlet by output<Consumer<Delta>>()
    }

    /** A declared cycle head whose Magnitude payload type witnesses damping (FU-8). */
    private class DeltaHead(override val ref: CellRef = fresh()) : Cell {
        val outlet by output<Consumer<Delta>>()
        val feedbackInput by feedbackInlet<Delta>(0.0) { }
    }

    private fun Plan.step(key: String): PlannedStep = steps.single { it.key == key }

    private fun StepCheck.refused(): StepCheck.Refused = shouldBeInstanceOf<StepCheck.Refused>()

    private fun spawn(handle: String, ref: CellRef, make: (CellRef) -> Cell) =
        SpawnStep(handle, CellFactory { make(it) }, IdentityBinding.Exact(ref))

    /** The reason a real connect of [shape] returns on a throwaway host (the reuse check). */
    private fun liveReason(shape: (Fixture) -> LinkResult): String {
        val throwaway = Fixture(seed = 999)
        try {
            return shape(throwaway).shouldBeInstanceOf<LinkResult.Rejected>().reason
        } finally {
            throwaway.host.managementInlet.call.drainHost()
        }
    }

    @Test
    fun `a test-source Contract carrying Owned gets a registered exclusive descriptor`() {
        ContractRegistry.descriptor(PrecheckOwnedPush::class.java)!!.methods.single().exclusive shouldBe true
    }

    // ---- AT_CAPACITY ----

    @Test
    fun `a boundary link into a live single-writer inlet that already has a producer is AT_CAPACITY`() {
        val f = Fixture(seed = 21)
        val producer = f.spawn(Src())
        val sink = f.spawn(SingleSink())
        f.connect(producer.ref, "outlet", sink.ref, "inlet").shouldBeInstanceOf<LinkResult.Connected>()
        val before = f.footprint()
        val stagedRef = fresh()

        val plan = GraphSpec(listOf(spawn("s", stagedRef) { Src(it) }))
            .precheck(listOf(BoundaryLink(sink.ref, "inlet", "s", "outlet", Direction.OUTBOUND)), f.view)

        f.footprint() shouldBe before
        val refused = plan.step("s.outlet->${sink.ref}.inlet").result.refused()
        refused.code shouldBe RefusalCode.AT_CAPACITY
        refused.reason shouldBe "single-writer inlet already has a producer (strict point-to-point, FU-6)"
        plan.verdict shouldBe Verdict.NotAppliable(listOf(plan.step("s.outlet->${sink.ref}.inlet")))

        refused.reason shouldBe liveReason { t ->
            val p = t.spawn(Src())
            val s = t.spawn(SingleSink())
            t.spawn(Src(stagedRef))
            t.connect(p.ref, "outlet", s.ref, "inlet")
            t.connect(stagedRef, "outlet", s.ref, "inlet")
        }
    }

    @Test
    fun `two planned producers into one staged single-writer inlet refuse the second`() {
        val f = Fixture(seed = 22)
        val before = f.footprint()
        val spec = GraphSpec(
            listOf(
                SpawnStep("a", CellFactory { Src(it) }),
                SpawnStep("b", CellFactory { Src(it) }),
                SpawnStep("sink", CellFactory { SingleSink(it) }),
                ConnectStep("a", "outlet", "sink", "inlet"),
                ConnectStep("b", "outlet", "sink", "inlet"),
            ),
        )

        val plan = spec.precheck(live = f.view)

        f.footprint() shouldBe before
        plan.step("a.outlet->sink.inlet").result shouldBe StepCheck.Ok
        val refused = plan.step("b.outlet->sink.inlet").result.refused()
        refused.code shouldBe RefusalCode.AT_CAPACITY
        refused.reason shouldBe "single-writer inlet already has a producer (strict point-to-point, FU-6)"
    }

    @Test
    fun `a boundary link into a FeedbackInlet that already has a producer is AT_CAPACITY`() {
        val f = Fixture(seed = 23)
        val producer = f.spawn(DeltaSrc())
        val head = f.spawn(DeltaHead())
        f.connect(producer.ref, "outlet", head.ref, "feedbackInput").shouldBeInstanceOf<LinkResult.Connected>()
        val before = f.footprint()

        val plan = GraphSpec(listOf(SpawnStep("s", CellFactory { DeltaSrc(it) })))
            .precheck(listOf(BoundaryLink(head.ref, "feedbackInput", "s", "outlet", Direction.OUTBOUND)), f.view)

        f.footprint() shouldBe before
        val refused = plan.step("s.outlet->${head.ref}.feedbackInput").result.refused()
        refused.code shouldBe RefusalCode.AT_CAPACITY
        refused.reason shouldBe "FeedbackInlet at capacity: already has an active producer (strict point-to-point)"
    }

    // ---- OWNERSHIP_VIOLATION ----

    private val spscReason =
        "SPSC (spec 23): ${PrecheckOwnedPush::class.java.name} carries Owned/Leased payloads; " +
            "outlet already has a subscriber"

    @Test
    fun `two planned links from one staged exclusive outlet refuse the second as OWNERSHIP_VIOLATION`() {
        val f = Fixture(seed = 31)
        val before = f.footprint()
        val spec = GraphSpec(
            listOf(
                SpawnStep("p", CellFactory { OwnedSrc(it) }),
                SpawnStep("q1", CellFactory { OwnedSink(it) }),
                SpawnStep("q2", CellFactory { OwnedSink(it) }),
                ConnectStep("p", "outlet", "q1", "inlet"),
                ConnectStep("p", "outlet", "q2", "inlet"),
            ),
        )

        val plan = spec.precheck(live = f.view)

        f.footprint() shouldBe before
        plan.step("p.outlet->q1.inlet").result shouldBe StepCheck.Ok
        val refused = plan.step("p.outlet->q2.inlet").result.refused()
        refused.code shouldBe RefusalCode.OWNERSHIP_VIOLATION
        refused.reason shouldBe spscReason
    }

    @Test
    fun `a staged exclusive outlet linked outbound twice refuses the second with the live SPSC reason`() {
        val f = Fixture(seed = 32)
        val l1 = f.spawn(OwnedSink())
        val l2 = f.spawn(OwnedSink())
        val before = f.footprint()
        val stagedRef = fresh()

        val plan = GraphSpec(listOf(spawn("p", stagedRef) { OwnedSrc(it) })).precheck(
            listOf(
                BoundaryLink(l1.ref, "inlet", "p", "outlet", Direction.OUTBOUND),
                BoundaryLink(l2.ref, "inlet", "p", "outlet", Direction.OUTBOUND),
            ),
            f.view,
        )

        f.footprint() shouldBe before
        plan.step("p.outlet->${l1.ref}.inlet").result shouldBe StepCheck.Ok
        val refused = plan.step("p.outlet->${l2.ref}.inlet").result.refused()
        refused.code shouldBe RefusalCode.OWNERSHIP_VIOLATION
        refused.reason shouldBe spscReason

        refused.reason shouldBe liveReason { t ->
            val a = t.spawn(OwnedSink())
            val b = t.spawn(OwnedSink())
            t.spawn(OwnedSrc(stagedRef))
            t.connect(stagedRef, "outlet", a.ref, "inlet").shouldBeInstanceOf<LinkResult.Connected>()
            t.connect(stagedRef, "outlet", b.ref, "inlet")
        }
    }

    @Test
    fun `a subscribed live exclusive outlet into a staged exclusive inlet is OWNED_INTAKE, not OWNERSHIP_VIOLATION`() {
        val f = Fixture(seed = 33)
        val src = f.spawn(OwnedSrc())
        val sub = f.spawn(OwnedSink())
        f.connect(src.ref, "outlet", sub.ref, "inlet").shouldBeInstanceOf<LinkResult.Connected>()
        val before = f.footprint()

        val plan = GraphSpec(listOf(SpawnStep("q", CellFactory { OwnedSink(it) })))
            .precheck(listOf(BoundaryLink(src.ref, "outlet", "q", "inlet", Direction.INBOUND)), f.view)

        f.footprint() shouldBe before
        plan.step("${src.ref}.outlet->q.inlet").result.refused().code shouldBe RefusalCode.OWNED_INTAKE
    }

    // ---- POLICY_DENIAL ----

    @Test
    fun `a target-side policy denial is POLICY_DENIAL carrying the policy's reason`() {
        val f = Fixture(seed = 41)
        val sink = f.spawn(Relay())
        sink.inlet.linking.policies += LinkPolicy { LinkResult.Rejected("blocked by policy") }
        val before = f.footprint()

        val plan = GraphSpec(listOf(SpawnStep("s", CellFactory { Src(it) })))
            .precheck(listOf(BoundaryLink(sink.ref, "inlet", "s", "outlet", Direction.OUTBOUND)), f.view)

        f.footprint() shouldBe before
        val refused = plan.step("s.outlet->${sink.ref}.inlet").result.refused()
        refused.code shouldBe RefusalCode.POLICY_DENIAL
        refused.reason shouldBe "blocked by policy"
    }

    @Test
    fun `a source-side policy denial is POLICY_DENIAL, and the target side wins when both deny`() {
        val f = Fixture(seed = 42)
        val src = f.spawn(Src())
        src.outlet.linking.policies += LinkPolicy { LinkResult.Rejected("source says no") }
        val before = f.footprint()

        val sourceOnly = GraphSpec(listOf(SpawnStep("r", CellFactory { Relay(it) })))
            .precheck(listOf(BoundaryLink(src.ref, "outlet", "r", "inlet", Direction.INBOUND)), f.view)

        val refused = sourceOnly.step("${src.ref}.outlet->r.inlet").result.refused()
        refused.code shouldBe RefusalCode.POLICY_DENIAL
        refused.reason shouldBe "source says no"

        val both = GraphSpec(
            listOf(
                SpawnStep(
                    "r",
                    CellFactory { ref ->
                        Relay(ref).also { it.inlet.linking.policies += LinkPolicy { LinkResult.Rejected("target says no") } }
                    },
                ),
            ),
        ).precheck(listOf(BoundaryLink(src.ref, "outlet", "r", "inlet", Direction.INBOUND)), f.view)

        both.step("${src.ref}.outlet->r.inlet").result.refused().reason shouldBe "target says no"
        f.footprint() shouldBe before
    }

    // ---- CYCLE_WITHOUT_HEAD ----

    @Test
    fun `a boundary link closing a cycle over live links is CYCLE_WITHOUT_HEAD with the live string`() {
        val f = Fixture(seed = 51)
        val a = f.spawn(Relay())
        val b = f.spawn(Relay())
        f.connect(a.ref, "outlet", b.ref, "inlet").shouldBeInstanceOf<LinkResult.Connected>()
        val before = f.footprint()
        val cRef = fresh()

        val plan = GraphSpec(listOf(spawn("c", cRef) { Relay(it) })).precheck(
            listOf(
                BoundaryLink(b.ref, "outlet", "c", "inlet", Direction.INBOUND),
                BoundaryLink(a.ref, "inlet", "c", "outlet", Direction.OUTBOUND),
            ),
            f.view,
        )

        f.footprint() shouldBe before
        plan.step("${b.ref}.outlet->c.inlet").result shouldBe StepCheck.Ok
        val refused = plan.step("c.outlet->${a.ref}.inlet").result.refused()
        refused.code shouldBe RefusalCode.CYCLE_WITHOUT_HEAD
        refused.reason shouldBe "CycleWithoutHead: connecting $cRef.outlet -> ${a.ref}.inlet would close a " +
            "locally-visible cycle with no declared CycleHead (spec 10/13, 20/21 §Cycles)"

        refused.reason shouldBe liveReason { t ->
            val ta = t.spawn(Relay(a.ref))
            val tb = t.spawn(Relay(b.ref))
            val tc = t.spawn(Relay(cRef))
            t.connect(ta.ref, "outlet", tb.ref, "inlet").shouldBeInstanceOf<LinkResult.Connected>()
            t.connect(tb.ref, "outlet", tc.ref, "inlet").shouldBeInstanceOf<LinkResult.Connected>()
            t.connect(tc.ref, "outlet", ta.ref, "inlet")
        }
    }

    @Test
    fun `a closing edge onto a damped FeedbackInlet head is admitted`() {
        val f = Fixture(seed = 52)
        val head = f.spawn(DeltaHead())
        val b = f.spawn(DeltaRelay())
        f.connect(head.ref, "outlet", b.ref, "inlet").shouldBeInstanceOf<LinkResult.Connected>()
        val before = f.footprint()

        val plan = GraphSpec(listOf(SpawnStep("c", CellFactory { DeltaRelay(it) }))).precheck(
            listOf(
                BoundaryLink(b.ref, "outlet", "c", "inlet", Direction.INBOUND),
                BoundaryLink(head.ref, "feedbackInput", "c", "outlet", Direction.OUTBOUND),
            ),
            f.view,
        )

        f.footprint() shouldBe before
        plan.verdict shouldBe Verdict.Appliable
    }

    @Test
    fun `a wholly spec-internal cycle is CYCLE_WITHOUT_HEAD, so planned links feed the walk`() {
        val f = Fixture(seed = 53)
        val before = f.footprint()
        val xRef = fresh()
        val yRef = fresh()
        val spec = GraphSpec(
            listOf(
                spawn("x", xRef) { Relay(it) },
                spawn("y", yRef) { Relay(it) },
                ConnectStep("x", "outlet", "y", "inlet"),
                ConnectStep("y", "outlet", "x", "inlet"),
            ),
        )

        val plan = spec.precheck(live = f.view)

        f.footprint() shouldBe before
        plan.step("x.outlet->y.inlet").result shouldBe StepCheck.Ok
        val refused = plan.step("y.outlet->x.inlet").result.refused()
        refused.code shouldBe RefusalCode.CYCLE_WITHOUT_HEAD
        refused.reason shouldBe "CycleWithoutHead: connecting $yRef.outlet -> $xRef.inlet would close a " +
            "locally-visible cycle with no declared CycleHead (spec 10/13, 20/21 §Cycles)"
    }

    // ---- OWNED_INTAKE ----

    @Test
    fun `an inbound boundary link into a staged exclusive inlet is OWNED_INTAKE, the outbound shape passes`() {
        val f = Fixture(seed = 61)
        val src = f.spawn(OwnedSrc())
        val sink = f.spawn(OwnedSink())
        val before = f.footprint()

        val inbound = GraphSpec(listOf(SpawnStep("q", CellFactory { OwnedSink(it) })))
            .precheck(listOf(BoundaryLink(src.ref, "outlet", "q", "inlet", Direction.INBOUND)), f.view)
        val refused = inbound.step("${src.ref}.outlet->q.inlet").result.refused()
        refused.code shouldBe RefusalCode.OWNED_INTAKE
        refused.reason shouldBe "boundary link into staged 'q'.inlet: the inlet carries Owned/Leased payloads, " +
            "and STAGE would consume an Owned/Leased payload that cannot be un-consumed ([WKB2-26])"

        val outbound = GraphSpec(listOf(SpawnStep("p", CellFactory { OwnedSrc(it) })))
            .precheck(listOf(BoundaryLink(sink.ref, "inlet", "p", "outlet", Direction.OUTBOUND)), f.view)
        outbound.verdict shouldBe Verdict.Appliable

        f.footprint() shouldBe before
    }

    private companion object {
        fun fresh() = CellRef(UUID.randomUUID())
    }
}
