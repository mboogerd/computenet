package civictech.cell.graph

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.op.CountCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import civictech.cell.link.reconcileNatures
import civictech.cell.partition.ShardCell
import civictech.cell.port.Port
import civictech.cell.port.PortNatures
import civictech.cell.port.PortRegistry
import civictech.cell.port.feedbackInlet
import civictech.cell.port.natures
import civictech.nature.NatureAxis
import civictech.nature.NatureVector
import civictech.nature.Ownership
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WKB2 F2 task 1 (computenet-91xzn.1): [GraphSpec.precheck] plans SPAWN/LINK
 * steps cold and refuses structural faults without touching the host
 * ([WKB2-10], [WKB2-11], [WKB2-13], [WKB2-14], [WKB2-28]).
 */
class PrecheckTest {

    private class Fixture(seed: Long) {
        val controller = SimulationController(seed = seed)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val view = HostLiveView(host, registry)

        /** A live SetCell linked into a live CountCell, so the topology is non-empty. */
        val liveSet = SetCell<String>().also { host.managementInlet.call.spawn(it) }
        val liveCount = CountCell<String>().also { host.managementInlet.call.spawn(it) }

        init {
            host.managementInlet.call.connect(liveSet.ref, "outlet", liveCount.ref, "inlet")
        }

        fun footprint() = Triple(host.subtreeCellCount(), registry.all(), registry.localRefs())
    }

    private fun port(cell: Any, name: String): Port = PortRegistry.of(cell)[name].shouldNotBeNull()

    private fun StepCheck.refused(): StepCheck.Refused = shouldBeInstanceOf<StepCheck.Refused>()

    private fun Plan.step(key: String): PlannedStep = steps.single { it.key == key }

    /**
     * A cell whose only port is a [civictech.cell.port.FeedbackInlet]: a
     * `LinkFrom` that is not a `LinkTo`, so naming it as an outlet is the
     * wrong-sided case. (Fan ports implement both sides, as the live
     * `LinkAdmission.connect` sees them.)
     */
    private class HeadOnly(override val ref: CellRef) : Cell {
        val feedback by feedbackInlet<String> { }
    }

    private val setFactory = CellFactory { ref -> SetCell<String>(ref = ref) }
    private val countFactory = CellFactory { ref -> CountCell<String>(ref = ref) }

    /** Two spawns and a connect: SetCell.outlet (Propagate<SetDelta>) → CountCell.inlet (Propagate<SetDelta>). */
    private fun twoSpawnsAndConnect(extra: List<GraphStep> = emptyList()) = GraphSpec(
        listOf(
            SpawnStep("set", setFactory),
            SpawnStep("count", countFactory),
            ConnectStep("set", "outlet", "count", "inlet"),
        ) + extra,
    )

    // ---- R1 [WKB2-10] + R5 [WKB2-14] ----

    @Test
    fun `an appliable plan lists every step in order and leaves the host untouched`() {
        val f = Fixture(seed = 11)
        f.registry.all().isEmpty() shouldBe false
        val before = f.footprint()

        val boundary = BoundaryLink(f.liveSet.ref, "outlet", "count", "inlet", Direction.INBOUND)
        val plan = twoSpawnsAndConnect().precheck(listOf(boundary), f.view)

        f.footprint() shouldBe before
        plan.verdict shouldBe Verdict.Appliable
        plan.steps.map { it.key } shouldBe listOf(
            "set",
            "count",
            "set.outlet->count.inlet",
            "${f.liveSet.ref}.outlet->count.inlet",
        )
        plan.steps.map { it.action } shouldBe
            listOf(PlannedAction.SPAWN, PlannedAction.SPAWN, PlannedAction.LINK, PlannedAction.LINK)
        plan.steps.map { it.touches } shouldBe listOf(emptySet(), emptySet(), emptySet(), setOf(f.liveSet.ref))
        plan.steps.map { it.result }.toSet() shouldBe setOf(StepCheck.Ok)
    }

    @Test
    fun `a not-appliable plan leaves the host untouched too`() {
        val f = Fixture(seed = 12)
        val before = f.footprint()

        // live SetCell.outlet (Propagate) into the staged SetCell's write inlet (SetOps): B1.
        val boundary = BoundaryLink(f.liveSet.ref, "outlet", "set", "inlet", Direction.INBOUND)
        val plan = twoSpawnsAndConnect().precheck(listOf(boundary), f.view)

        f.footprint() shouldBe before
        plan.verdict.shouldBeInstanceOf<Verdict.NotAppliable>()
    }

    // ---- R2 [WKB2-11] / B1 ----

    @Test
    fun `a payload-class mismatch across the boundary is CONTRACT_MISMATCH with the checkPayload string`() {
        val f = Fixture(seed = 13)
        val before = f.footprint()
        var staged: SetCell<String>? = null
        val spec = GraphSpec(listOf(SpawnStep("set", CellFactory { ref -> SetCell<String>(ref = ref).also { staged = it } })))

        val plan = spec.precheck(listOf(BoundaryLink(f.liveSet.ref, "outlet", "set", "inlet", Direction.INBOUND)), f.view)

        val refused = plan.step("${f.liveSet.ref}.outlet->set.inlet").result.refused()
        refused.code shouldBe RefusalCode.CONTRACT_MISMATCH
        refused.reason shouldBe "payload mismatch: ${Propagate::class.java.name} -> ${SetOps::class.java.name} " +
            "at ${port(f.liveSet, "outlet").ref} -> ${port(staged!!, "inlet").ref}"
        refused.mismatch shouldBe null
        plan.verdict shouldBe Verdict.NotAppliable(listOf(plan.step("${f.liveSet.ref}.outlet->set.inlet")))
        f.footprint() shouldBe before
    }

    @Test
    fun `a spec-internal payload mismatch is CONTRACT_MISMATCH too`() {
        val f = Fixture(seed = 14)
        val plan = GraphSpec(
            listOf(SpawnStep("a", setFactory), SpawnStep("b", setFactory), ConnectStep("a", "outlet", "b", "inlet")),
        ).precheck(live = f.view)

        val refused = plan.step("a.outlet->b.inlet").result.refused()
        refused.code shouldBe RefusalCode.CONTRACT_MISMATCH
        refused.reason shouldContain "payload mismatch: ${Propagate::class.java.name} -> ${SetOps::class.java.name}"
    }

    @Test
    fun `unknown handles are UNRESOLVED_HANDLE`() {
        val f = Fixture(seed = 15)
        val plan = GraphSpec(
            listOf(
                SpawnStep("set", setFactory),
                ConnectStep("set", "outlet", "ghost", "inlet"),
                SpawnStep("child", countFactory, parent = "nobody"),
            ),
        ).precheck(listOf(BoundaryLink(f.liveSet.ref, "outlet", "phantom", "inlet", Direction.INBOUND)), f.view)

        plan.step("set.outlet->ghost.inlet").result.refused().code shouldBe RefusalCode.UNRESOLVED_HANDLE
        plan.step("set.outlet->ghost.inlet").result.refused().reason shouldContain "'ghost'"
        plan.step("child").result.refused().code shouldBe RefusalCode.UNRESOLVED_HANDLE
        plan.step("child").result.refused().reason shouldContain "'nobody'"
        plan.step("${f.liveSet.ref}.outlet->phantom.inlet").result.refused().code shouldBe RefusalCode.UNRESOLVED_HANDLE
    }

    @Test
    fun `absent and wrong-sided ports are UNRESOLVED_PORT with the LinkAdmission wording`() {
        val f = Fixture(seed = 16)
        val plan = twoSpawnsAndConnect(
            listOf(
                ConnectStep("set", "nope", "count", "inlet"),
                SpawnStep("head", CellFactory { ref -> HeadOnly(ref) }),
                // "feedback" is registered on the cell but is a LinkFrom only
                ConnectStep("head", "feedback", "count", "inlet"),
                ConnectStep("set", "outlet", "count", "absent"),
            ),
        ).precheck(
            listOf(
                BoundaryLink(f.liveSet.ref, "missing", "count", "inlet", Direction.INBOUND),
                BoundaryLink(f.liveCount.ref, "absent", "set", "outlet", Direction.OUTBOUND),
                BoundaryLink(f.liveSet.ref, "outlet", "head", "absent", Direction.INBOUND),
            ),
            f.view,
        )

        plan.step("set.nope->count.inlet").result shouldBe
            StepCheck.Refused(RefusalCode.UNRESOLVED_PORT, "Outlet not found or not linkable: nope on set")
        plan.step("head.feedback->count.inlet").result shouldBe
            StepCheck.Refused(RefusalCode.UNRESOLVED_PORT, "Outlet not found or not linkable: feedback on head")
        plan.step("set.outlet->count.absent").result shouldBe
            StepCheck.Refused(RefusalCode.UNRESOLVED_PORT, "Inlet not found or not linkable: absent on count")
        plan.step("${f.liveSet.ref}.missing->count.inlet").result shouldBe
            StepCheck.Refused(RefusalCode.UNRESOLVED_PORT, "Outlet not found or not linkable: missing on ${f.liveSet.ref}")
        plan.step("set.outlet->${f.liveCount.ref}.absent").result shouldBe
            StepCheck.Refused(RefusalCode.UNRESOLVED_PORT, "Inlet not found or not linkable: absent on ${f.liveCount.ref}")
        plan.step("${f.liveSet.ref}.outlet->head.absent").result shouldBe
            StepCheck.Refused(RefusalCode.UNRESOLVED_PORT, "Inlet not found or not linkable: absent on head")
    }

    // ---- natures (CP-F3) ----

    @Test
    fun `a nature refusal is CONTRACT_MISMATCH carrying the typed NatureMismatch`() {
        val f = Fixture(seed = 17)
        var staged: CountCell<String>? = null
        // the consumer requires an EXCLUSIVE-ownership producer; SetCell.outlet offers less.
        val exclusiveCount = CellFactory { ref ->
            CountCell<String>(ref = ref).also {
                PortNatures.stamp(port(it, "inlet"), NatureVector.of(Ownership.EXCLUSIVE))
                staged = it
            }
        }
        val plan = GraphSpec(
            listOf(SpawnStep("set", setFactory), SpawnStep("count", exclusiveCount), ConnectStep("set", "outlet", "count", "inlet")),
        ).precheck(listOf(BoundaryLink(f.liveSet.ref, "outlet", "count", "inlet", Direction.INBOUND)), f.view)

        val expected = reconcileNatures(port(f.liveSet, "outlet").natures, port(staged!!, "inlet").natures).shouldNotBeNull()
        listOf("set.outlet->count.inlet", "${f.liveSet.ref}.outlet->count.inlet").forEach { key ->
            val refused = plan.step(key).result.refused()
            refused.code shouldBe RefusalCode.CONTRACT_MISMATCH
            refused.reason shouldBe expected.reason
            refused.mismatch.shouldNotBeNull().axis shouldBe NatureAxis.OWNERSHIP
            refused.mismatch shouldBe expected.mismatch
        }
    }

    // ---- spawn refusals ----

    @Test
    fun `an Exact spawn of a live ref is LIVE_REF, reported cold`() {
        val f = Fixture(seed = 18)
        val before = f.footprint()
        val plan = GraphSpec(listOf(SpawnStep("dup", setFactory, IdentityBinding.Exact(f.liveSet.ref)))).precheck(live = f.view)

        val refused = plan.step("dup").result.refused()
        refused.code shouldBe RefusalCode.LIVE_REF
        refused.reason shouldContain f.liveSet.ref.toString()
        refused.reason shouldContain "[15-APPLY-01]"
        f.footprint() shouldBe before
    }

    @Test
    fun `a factory that ignores its Exact ref is CONTRACT_MISMATCH with the requireBoundRef message`() {
        val f = Fixture(seed = 19)
        val chosen = CellRef(UUID.randomUUID())
        val step = SpawnStep("rogue", CellFactory { SetCell<String>() }, IdentityBinding.Exact(chosen))
        val plan = GraphSpec(listOf(step)).precheck(live = f.view)

        val refused = plan.step("rogue").result.refused()
        refused.code shouldBe RefusalCode.CONTRACT_MISMATCH
        refused.reason shouldContain "spawn step 'rogue': factory must construct a cell with ref $chosen"
    }

    // ---- boundary refs ----

    @Test
    fun `a boundary ref the view does not locate is UNKNOWN_REF`() {
        val f = Fixture(seed = 20)
        val stranger = CellRef(UUID.randomUUID())
        val plan = twoSpawnsAndConnect().precheck(listOf(BoundaryLink(stranger, "outlet", "count", "inlet", Direction.INBOUND)), f.view)

        plan.step("$stranger.outlet->count.inlet").result.refused().code shouldBe RefusalCode.UNKNOWN_REF
    }

    @Test
    fun `a suspended boundary ref is UNREACHABLE_REF`() {
        val f = Fixture(seed = 21)
        f.host.managementInlet.call.suspend(f.liveSet.ref)
        f.controller.runToIdle()
        f.host.isSuspended(f.liveSet.ref) shouldBe true
        val plan = twoSpawnsAndConnect()
            .precheck(listOf(BoundaryLink(f.liveSet.ref, "outlet", "count", "inlet", Direction.INBOUND)), f.view)

        plan.step("${f.liveSet.ref}.outlet->count.inlet").result.refused().code shouldBe RefusalCode.UNREACHABLE_REF
    }

    @Test
    fun `a boundary ref on a drained host is UNREACHABLE_REF`() {
        val f = Fixture(seed = 22)
        f.host.managementInlet.call.drainHost()
        f.controller.runToIdle()
        f.host.isDrained shouldBe true
        val plan = twoSpawnsAndConnect()
            .precheck(listOf(BoundaryLink(f.liveSet.ref, "outlet", "count", "inlet", Direction.INBOUND)), f.view)

        plan.step("${f.liveSet.ref}.outlet->count.inlet").result.refused().code shouldBe RefusalCode.UNREACHABLE_REF
    }

    @Test
    fun `a boundary ref local to another host is MULTI_HOST naming G-61`() {
        val f = Fixture(seed = 23)
        val other = ManagedHost(scheduler = f.controller.scheduler(), registry = f.registry)
        val foreign = SetCell<String>().also { other.managementInlet.call.spawn(it) }

        val plan = twoSpawnsAndConnect().precheck(
            listOf(
                BoundaryLink(f.liveSet.ref, "outlet", "count", "inlet", Direction.INBOUND),
                BoundaryLink(foreign.ref, "outlet", "count", "inlet", Direction.INBOUND),
            ),
            f.view,
        )

        plan.step("${f.liveSet.ref}.outlet->count.inlet").result shouldBe StepCheck.Ok
        val refused = plan.step("${foreign.ref}.outlet->count.inlet").result.refused()
        refused.code shouldBe RefusalCode.MULTI_HOST
        refused.reason shouldContain "G-61"
    }

    // ---- R4 [WKB2-13] ----

    private fun thrownBy(step: InstanceSetStep): String =
        shouldThrow<IllegalArgumentException> { step.lower() }.message.shouldNotBeNull()

    @Test
    fun `an InstanceSetStep partitioning a SINGLETON cell is INSTANCE_SCOPING, message verbatim`() {
        val f = Fixture(seed = 24)
        val step = InstanceSetStep(
            "s", UUID.randomUUID(), InstanceFactory { ref, _ -> SetCell<String>(ref = ref) },
            listOf(
                InstanceSpec(Interest.Slots(setOf(0), 2), 0, journalId = "j0"),
                InstanceSpec(Interest.Slots(setOf(1), 2), 1, journalId = "j1"),
            ),
        )
        val plan = GraphSpec(listOf(step)).precheck(live = f.view)

        plan.steps.map { it.key } shouldBe listOf("s")
        plan.step("s").result shouldBe StepCheck.Refused(RefusalCode.INSTANCE_SCOPING, thrownBy(step))
    }

    @Test
    fun `an InstanceSetStep declaring a DURABLE cell journal-less is DURABLE, message verbatim`() {
        val f = Fixture(seed = 25)
        val step = InstanceSetStep(
            "d", UUID.randomUUID(), InstanceFactory { ref, spec -> ShardCell<String>(ref, { it }, spec.interest) },
            listOf(InstanceSpec(Interest.Total, 0, journalId = null)),
        )
        val plan = GraphSpec(listOf(step)).precheck(live = f.view)

        plan.step("d").result shouldBe StepCheck.Refused(RefusalCode.DURABLE, thrownBy(step))
    }

    @Test
    fun `a valid InstanceSetStep plans one SPAWN per lowered instance`() {
        val f = Fixture(seed = 26)
        val step = InstanceSetStep(
            "r", UUID.randomUUID(), InstanceFactory { ref, spec -> ShardCell<String>(ref, { it }, spec.interest) },
            listOf(InstanceSpec(Interest.Total, 0, journalId = "j0"), InstanceSpec(Interest.Total, 1, journalId = "j1")),
        )
        val plan = GraphSpec(listOf(step)).precheck(live = f.view)

        plan.steps.map { it.key to it.action } shouldBe listOf("r-0" to PlannedAction.SPAWN, "r-1" to PlannedAction.SPAWN)
        plan.verdict shouldBe Verdict.Appliable
    }

    // ---- R5: every refusal, not first-failure ----

    @Test
    fun `a not-appliable verdict lists every refused step in plan order`() {
        val f = Fixture(seed = 27)
        val stranger = CellRef(UUID.randomUUID())
        val plan = twoSpawnsAndConnect(
            listOf(
                ConnectStep("set", "outlet", "ghost", "inlet"),
                SpawnStep("dup", setFactory, IdentityBinding.Exact(f.liveSet.ref)),
            ),
        ).precheck(
            listOf(
                BoundaryLink(f.liveSet.ref, "outlet", "set", "inlet", Direction.INBOUND),
                BoundaryLink(stranger, "outlet", "count", "inlet", Direction.INBOUND),
            ),
            f.view,
        )

        val refusals = plan.verdict.shouldBeInstanceOf<Verdict.NotAppliable>().refusals
        refusals.map { it.key to it.result.refused().code } shouldBe listOf(
            "set.outlet->ghost.inlet" to RefusalCode.UNRESOLVED_HANDLE,
            "dup" to RefusalCode.LIVE_REF,
            "${f.liveSet.ref}.outlet->set.inlet" to RefusalCode.CONTRACT_MISMATCH,
            "$stranger.outlet->count.inlet" to RefusalCode.UNKNOWN_REF,
        )
        plan.steps.size shouldBe 7
        plan.step("set.outlet->count.inlet").result shouldBe StepCheck.Ok
    }
}
