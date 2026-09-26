package civictech.inspect.edit

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.graph.BoundaryLink
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.Direction
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.GraphStep
import civictech.cell.graph.SpawnStep
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.TopologyLink
import civictech.inspect.InspectorServer
import civictech.inspect.inspectorJson
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WKB2 F3 task 1 (`computenet-e1ojt.1`): [StagedApplier]'s phase machine and
 * its registry-level rules. Every host runs on a [SimulationController]
 * scheduler with a fixed seed. The applier awaits every management verb it
 * drives (spawnBound and connect are awaited by the host; its despawns are
 * bracketed by awaited barriers), so no bounded wait is needed; the tests'
 * own fire-and-forget verbs (drainHost, suspend) are flushed with
 * `runToIdle`.
 *
 * Verb order is read from [LocationRegistry]'s `onLocalPublish` /
 * `onLocalUnpublish` / `onLocalTopology` hooks (see `FailureInjection.kt`
 * for why that seam and not a management-API wrapper). Identity is asserted
 * at registry level — `localRefs()` and `all()` — per e1ojt-D9; the
 * inspector-route identity check (`[WKB2-19]`) is the sibling task's.
 */
class StagedApplierTest {

    private class Fixture(seed: Long) {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        private var now = 1_000L
        val events = CopyOnWriteArrayList<String>()
        private val names = mutableMapOf<CellRef, String>()

        init {
            registry.onLocalPublish { events += "publish:${nameOf(it)}" }
            registry.onLocalUnpublish { events += "unpublish:${nameOf(it)}" }
            registry.onLocalTopology({ events += "link:${nameOf(it.from.cell)}->${nameOf(it.to.cell)}" }, { events += "unlink" })
        }

        fun nameOf(ref: CellRef?): String = ref?.let { names[it] } ?: ref?.let(InspectorServer::encodeRef) ?: "?"

        /** Spawns a pre-existing live cell, named [name] in [events]. */
        fun <C : Cell> live(name: String, cell: C): C {
            names[cell.ref] = name
            host.managementInlet.call.spawn(cell)
            return cell
        }

        /** Names the staged refs of [record] by their spec handles, in creation order. */
        fun nameStaged(record: ApplyRecord, handles: List<String>) {
            record.stagedRefs.zip(handles).forEach { (encoded, handle) ->
                names[InspectorServer.decodeRef(encoded)!!] = handle
            }
        }

        fun applier(
            skipPrecheck: Boolean = false,
            afterStage: (ApplyRecord) -> Unit = {},
            beforeBoundaryLink: (Int) -> Unit = {},
        ) = StagedApplier(
            hosts = mapOf(HOST to host),
            registry = registry,
            clock = { now++ },
            skipPrecheck = skipPrecheck,
            afterStage = afterStage,
            beforeBoundaryLink = beforeBoundaryLink,
        )
    }

    private fun spec(vararg steps: GraphStep) = GraphSpec(steps.toList())

    private fun sink(handle: String, onCreate: () -> Unit = {}) =
        SpawnStep(handle, CellFactory { ref -> onCreate(); SinkCell(ref) })

    private fun emitter(handle: String, onCreate: () -> Unit = {}) =
        SpawnStep(handle, CellFactory { ref -> onCreate(); EmitterCell(ref) })

    /** Fails in STAGE: call 1 is PRECHECK's cold construction (see [FailingFactory]). */
    private fun failingSink(handle: String) = SpawnStep(handle, FailingFactory(2) { ref -> SinkCell(ref) })

    private fun outbound(handle: String, live: CellRef, livePort: String = "inlet") =
        BoundaryLink(live, livePort, handle, "outlet", Direction.OUTBOUND)

    // ---- [WKB2-15] -----------------------------------------------------------

    @Test
    fun `WKB2-15 a precheck refusal returns refused-at-precheck with the whole plan and nothing applied`() {
        val f = Fixture(seed = 15)
        val a = f.live("A", SinkCell())
        val refsBefore = f.registry.localRefs()
        val linksBefore = f.registry.all()
        val unknown = CellRef(UUID.randomUUID())

        val record = f.applier().apply(
            Draft(
                HOST,
                spec(emitter("e"), emitter("e2")),
                boundary = listOf(outbound("e", a.ref), outbound("e2", unknown)),
            ),
            applyId = "a-15", identity = "operator", baseTopologyVersion = 3,
        )

        record.outcome shouldBe ApplyOutcome.RefusedAtPrecheck
        record.phase shouldBe ApplyPhase.PRECHECK
        val plan = record.plan.shouldNotBeNull()
        plan.appliable shouldBe false
        plan.steps.map { it.key } shouldContainExactly listOf(
            "e", "e2", "e.outlet->${a.ref}.inlet", "e2.outlet->$unknown.inlet",
        )
        plan.steps.map { it.refusal?.code } shouldContainExactly listOf(null, null, null, "UNKNOWN_REF")
        plan.steps[3].touches shouldContainExactly listOf(InspectorServer.encodeRef(unknown))
        record.steps.keys.toList() shouldContainExactly plan.steps.map { it.key }
        record.steps.values.toSet() shouldBe setOf(StepOutcome.NotRun)
        record.stagedRefs.shouldBeEmpty()
        record.completedAtMs.shouldNotBeNull()
        f.registry.localRefs() shouldBe refsBefore
        f.registry.all() shouldBe linksBefore
    }

    // ---- [WKB2-16] -----------------------------------------------------------

    /** Records each phase the applier's record is observed in, collapsing repeats. */
    private class PhaseTrace {
        lateinit var applier: StagedApplier
        val phases = mutableListOf<ApplyPhase>()

        fun sample(applyId: String) {
            val phase = applier.record(applyId)?.phase ?: return
            if (phases.lastOrNull() != phase) phases += phase
        }
    }

    @Test
    fun `WKB2-16 a successful apply passes PRECHECK, STAGE, CUT_OVER, RETIRE in that order`() {
        val f = Fixture(seed = 16)
        val a = f.live("A", SinkCell())
        val trace = PhaseTrace()
        trace.applier = f.applier(
            afterStage = { trace.sample("a-16") },
            beforeBoundaryLink = { trace.sample("a-16") },
        )

        val record = trace.applier.apply(
            Draft(
                HOST,
                spec(
                    emitter("e") { trace.sample("a-16") },
                    sink("k") { trace.sample("a-16") },
                    ConnectStep("e", "outlet", "k", "inlet"),
                ),
                boundary = listOf(outbound("e", a.ref)),
            ),
            applyId = "a-16", identity = "operator", baseTopologyVersion = 1,
        )
        trace.sample("a-16")

        record.outcome shouldBe ApplyOutcome.Committed
        trace.phases shouldContainExactly listOf(ApplyPhase.PRECHECK, ApplyPhase.STAGE, ApplyPhase.CUT_OVER, ApplyPhase.RETIRE)
        record.steps.values.toSet() shouldBe setOf(StepOutcome.Applied)
    }

    @Test
    fun `WKB2-16 a STAGE failure passes PRECHECK, STAGE, UNWIND in that order`() {
        val f = Fixture(seed = 161)
        val trace = PhaseTrace()
        trace.applier = f.applier(
            afterStage = { trace.sample("a-161") },
            beforeBoundaryLink = { trace.sample("a-161") },
        )
        f.registry.onLocalUnpublish { trace.sample("a-161") }

        val record = trace.applier.apply(
            Draft(HOST, spec(sink("s1") { trace.sample("a-161") }, failingSink("s2"))),
            applyId = "a-161", identity = "operator", baseTopologyVersion = 1,
        )
        trace.sample("a-161")

        record.outcome shouldBe ApplyOutcome.UnwoundClean
        trace.phases shouldContainExactly listOf(ApplyPhase.PRECHECK, ApplyPhase.STAGE, ApplyPhase.UNWIND)
    }

    // ---- [WKB2-17] -----------------------------------------------------------

    @Test
    fun `WKB2-17 STAGE reports no link touching a live cell until CUT_OVER begins`() {
        val f = Fixture(seed = 17)
        val a = f.live("A", SinkCell())
        val liveRefs = f.registry.localRefs()
        val linked = CopyOnWriteArrayList<TopologyLink>()
        f.registry.onLocalTopology({ linked += it }, {})
        fun touchingLive() = linked.filter { it.from.cell in liveRefs || it.to.cell in liveRefs }
        var atAfterStage: List<TopologyLink>? = null
        var atCutOver: List<TopologyLink>? = null
        var internalAtAfterStage = 0

        val record = f.applier(
            afterStage = { atAfterStage = touchingLive(); internalAtAfterStage = linked.size },
            beforeBoundaryLink = { if (it == 0) atCutOver = touchingLive() },
        ).apply(
            Draft(
                HOST,
                spec(emitter("e"), sink("k"), ConnectStep("e", "outlet", "k", "inlet")),
                boundary = listOf(outbound("e", a.ref)),
            ),
            applyId = "a-17", identity = "operator", baseTopologyVersion = 1,
        )

        record.outcome shouldBe ApplyOutcome.Committed
        internalAtAfterStage shouldBe 1 // the spec-internal link did land during STAGE
        atAfterStage.shouldNotBeNull().shouldBeEmpty()
        atCutOver.shouldNotBeNull().shouldBeEmpty()
        touchingLive().size shouldBe 1 // the boundary link, made in CUT_OVER
    }

    @Test
    fun `WKB2-17 a STAGE failure leaves the live boundary endpoint's swap set unchanged`() {
        val f = Fixture(seed = 171)
        val a = f.live("A", SinkCell())
        val swapBefore = f.registry.swapSet(a.ref)

        val record = f.applier().apply(
            Draft(
                HOST,
                spec(emitter("e"), failingSink("k"), ConnectStep("e", "outlet", "k", "inlet")),
                boundary = listOf(outbound("e", a.ref)),
            ),
            applyId = "a-171", identity = "operator", baseTopologyVersion = 1,
        )

        record.outcome shouldBe ApplyOutcome.UnwoundClean
        f.registry.swapSet(a.ref) shouldBe swapBefore
        f.events.none { it.startsWith("link:") && "A" in it } shouldBe true
        record.steps.getValue("e.outlet->${a.ref}.inlet") shouldBe StepOutcome.NotRun
    }

    // ---- [WKB2-18] -----------------------------------------------------------

    @Test
    fun `WKB2-18 a failed third spawn unlinks the internal link and despawns the earlier cells in reverse order`() {
        val f = Fixture(seed = 18)
        f.live("A", SinkCell())
        val refsBefore = f.registry.localRefs()
        val linksBefore = f.registry.all()
        var stagedSnapshot: ApplyRecord? = null
        lateinit var applier: StagedApplier
        // Name the staged cells as soon as both exist, so the hook log reads by handle.
        f.registry.onLocalTopology({ applier.record("a-18")?.let { stagedSnapshot = it; f.nameStaged(it, listOf("s1", "s2")) } }, {})
        applier = f.applier()
        f.events.clear()

        val record = applier.apply(
            Draft(
                HOST,
                spec(emitter("s1"), sink("s2"), ConnectStep("s1", "outlet", "s2", "inlet"), failingSink("s3")),
            ),
            applyId = "a-18", identity = "operator", baseTopologyVersion = 1,
        )

        stagedSnapshot.shouldNotBeNull()
        record.outcome shouldBe ApplyOutcome.UnwoundClean
        record.phase shouldBe ApplyPhase.UNWIND
        // publish order from the hooks: the link event fires before this test's
        // naming hook runs, so it is logged with raw refs — assert its shape only.
        f.events.size shouldBe 6
        f.events[0].startsWith("publish:") shouldBe true
        f.events[1].startsWith("publish:") shouldBe true
        f.events[2].startsWith("link:") shouldBe true
        f.events.drop(3) shouldContainExactly listOf("unlink", "unpublish:s2", "unpublish:s1")
        record.stagedRefs.size shouldBe 2
        record.steps shouldBe mapOf(
            "s1" to StepOutcome.Unwound,
            "s2" to StepOutcome.Unwound,
            "s1.outlet->s2.inlet" to StepOutcome.Unwound,
            "s3" to StepOutcome.Failed("injected"),
        )
        f.registry.localRefs() shouldBe refsBefore
        f.registry.all() shouldBe linksBefore
    }

    // ---- [WKB2-57] -----------------------------------------------------------

    @Test
    fun `WKB2-57 abort during STAGE runs the same unwind and reports unwound-clean`() {
        val f = Fixture(seed = 57)
        val a = f.live("A", SinkCell())
        val refsBefore = f.registry.localRefs()
        val linksBefore = f.registry.all()
        lateinit var applier: StagedApplier
        var aborted: Boolean? = null
        var boundaryAttempted = false
        applier = f.applier(
            afterStage = { rec ->
                rec.phase shouldBe ApplyPhase.STAGE
                aborted = applier.abort(rec.applyId)
            },
            beforeBoundaryLink = { boundaryAttempted = true },
        )

        val record = applier.apply(
            Draft(
                HOST,
                spec(emitter("e"), sink("k"), ConnectStep("e", "outlet", "k", "inlet")),
                boundary = listOf(outbound("e", a.ref)),
            ),
            applyId = "a-57", identity = "operator", baseTopologyVersion = 1,
        )

        aborted shouldBe true
        boundaryAttempted shouldBe false
        record.outcome shouldBe ApplyOutcome.UnwoundClean
        record.phase shouldBe ApplyPhase.UNWIND
        record.steps.getValue("e") shouldBe StepOutcome.Unwound
        record.steps.getValue("k") shouldBe StepOutcome.Unwound
        record.steps.getValue("e.outlet->k.inlet") shouldBe StepOutcome.Unwound
        f.registry.localRefs() shouldBe refsBefore
        f.registry.all() shouldBe linksBefore
    }

    @Test
    fun `WKB2-57 abort of a record not in STAGE returns false and changes nothing`() {
        val f = Fixture(seed = 571)
        val a = f.live("A", SinkCell())
        lateinit var applier: StagedApplier
        var duringCutOver: Boolean? = null
        applier = f.applier(beforeBoundaryLink = { duringCutOver = applier.abort("a-571") })

        applier.abort("never-submitted") shouldBe false
        val record = applier.apply(
            Draft(HOST, spec(emitter("e")), boundary = listOf(outbound("e", a.ref))),
            applyId = "a-571", identity = "operator", baseTopologyVersion = 1,
        )

        duringCutOver shouldBe false
        record.outcome shouldBe ApplyOutcome.Committed
        val refsAfter = f.registry.localRefs()
        val linksAfter = f.registry.all()
        applier.abort("a-571") shouldBe false
        applier.record("a-571") shouldBe record
        f.registry.localRefs() shouldBe refsAfter
        f.registry.all() shouldBe linksAfter
    }

    // ---- [WKB2-58] -----------------------------------------------------------

    @Test
    fun `WKB2-58 a drained target host is refused UNREACHABLE_REF and stays drained`() {
        val f = Fixture(seed = 58)
        f.host.managementInlet.call.drainHost()
        f.controller.runToIdle()
        f.host.isDrained shouldBe true
        f.events.clear()

        val record = f.applier().apply(
            Draft(HOST, spec(sink("k"))),
            applyId = "a-58", identity = "operator", baseTopologyVersion = 1,
        )
        f.controller.runToIdle()

        record.outcome shouldBe ApplyOutcome.RefusedAtPrecheck
        record.plan.shouldNotBeNull().steps shouldContainExactly listOf(
            PlannedStepDto("host", null, "SPAWN", emptyList(), RefusalDto("UNREACHABLE_REF", "target host '$HOST' is drained")),
        )
        record.steps shouldBe mapOf("host" to StepOutcome.NotRun)
        f.host.isDrained shouldBe true
        f.events.shouldBeEmpty()
    }

    @Test
    fun `WKB2-58 a suspended boundary ref or despawn target is refused UNREACHABLE_REF and stays suspended`() {
        val f = Fixture(seed = 581)
        val a = f.live("A", SinkCell())
        val b = f.live("B", SinkCell())
        f.host.managementInlet.call.suspend(a.ref)
        f.host.managementInlet.call.suspend(b.ref)
        f.controller.runToIdle() // management suspend is fire-and-forget
        val refsBefore = f.registry.localRefs()

        val boundaryRecord = f.applier().apply(
            Draft(HOST, spec(emitter("e")), boundary = listOf(outbound("e", a.ref))),
            applyId = "a-581", identity = "operator", baseTopologyVersion = 1,
        )
        val despawnRecord = f.applier().apply(
            Draft(HOST, spec(), despawns = listOf(b.ref)),
            applyId = "a-582", identity = "operator", baseTopologyVersion = 1,
        )
        f.controller.runToIdle()

        boundaryRecord.outcome shouldBe ApplyOutcome.RefusedAtPrecheck
        boundaryRecord.plan.shouldNotBeNull().steps.last().refusal?.code shouldBe "UNREACHABLE_REF"
        despawnRecord.outcome shouldBe ApplyOutcome.RefusedAtPrecheck
        despawnRecord.plan.shouldNotBeNull().steps.single().let {
            it.key shouldBe "despawn:${InspectorServer.encodeRef(b.ref)}"
            it.action shouldBe "DESPAWN"
            it.refusal?.code shouldBe "UNREACHABLE_REF"
        }
        f.host.isSuspended(a.ref) shouldBe true
        f.host.isSuspended(b.ref) shouldBe true
        f.registry.localRefs() shouldBe refsBefore
    }

    @Test
    fun `WKB2-58 the applier source never names the resume verbs`() {
        val source = File("src/main/kotlin/civictech/inspect/edit/StagedApplier.kt")
        source.isFile shouldBe true
        source.readText() shouldNotContain "resume"
    }

    // ---- [WKB2-24] -----------------------------------------------------------

    @Test
    fun `WKB2-24 committed is recorded before the first despawn, and a throwing despawn is Failed under Committed`() {
        val f = Fixture(seed = 24)
        val a = f.live("A", SinkCell())
        val old = f.live("OLD", SinkCell())
        val throwing = f.live("T", ThrowingDeactivateCell())
        lateinit var applier: StagedApplier
        val atFirstDespawn = mutableListOf<ApplyRecord?>()
        f.registry.onLocalUnpublish { if (it == old.ref) atFirstDespawn += applier.record("a-24") }
        applier = f.applier()

        val record = applier.apply(
            Draft(
                HOST,
                spec(emitter("e")),
                boundary = listOf(outbound("e", a.ref)),
                despawns = listOf(old.ref, throwing.ref),
            ),
            applyId = "a-24", identity = "operator", baseTopologyVersion = 1,
        )

        val seen = atFirstDespawn.single().shouldNotBeNull()
        seen.outcome shouldBe ApplyOutcome.Committed
        seen.phase shouldBe ApplyPhase.RETIRE
        seen.steps.getValue("despawn:${InspectorServer.encodeRef(old.ref)}") shouldBe StepOutcome.NotRun
        record.outcome shouldBe ApplyOutcome.Committed
        record.phase shouldBe ApplyPhase.RETIRE
        record.steps.getValue("despawn:${InspectorServer.encodeRef(old.ref)}") shouldBe StepOutcome.Applied
        record.steps.getValue("despawn:${InspectorServer.encodeRef(throwing.ref)}") shouldBe
            StepOutcome.Failed("despawn of ${throwing.ref} dead-lettered on the target host (1 fault(s))")
        (old.ref in f.registry.localRefs()) shouldBe false
    }

    // ---- record shape ----------------------------------------------------------

    @Test
    fun `a terminal record round-trips through inspectorJson, and a pre-F3 payload still decodes`() {
        val f = Fixture(seed = 4)
        val record = f.applier().apply(
            Draft(HOST, spec(sink("s1"), failingSink("s2"))),
            applyId = "a-4", identity = "operator", baseTopologyVersion = 1,
        )

        inspectorJson.decodeFromString<ApplyRecord>(inspectorJson.encodeToString(record)) shouldBe record

        val preF3 = """{"applyId":"x","identity":"op","submittedDraft":null,"baseTopologyVersion":1,"submittedAtMs":0}"""
        val decoded = inspectorJson.decodeFromString<ApplyRecord>(preF3)
        decoded.phase shouldBe ApplyPhase.PRECHECK
        decoded.outcome shouldBe null
        decoded.plan shouldBe null
        decoded.stagedRefs.shouldBeEmpty()
    }

    private companion object {
        const val HOST = "h1"
    }
}
