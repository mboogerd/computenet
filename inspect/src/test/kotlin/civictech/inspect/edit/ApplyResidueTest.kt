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
import civictech.cell.host.VirtualThreadScheduler
import civictech.inspect.InspectorServer
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * WKB2 F3 task 2 (`computenet-e1ojt.2`): `[WKB2-25]` — an unwind that could
 * not undo an effect says so. Residue is never rounded to
 * [ApplyOutcome.UnwoundClean]: every test that injects an escaping effect
 * asserts `outcome is UnwoundWithResidue`, and each has a control that differs
 * only in the injected effect and ends clean, so the residue arm is shown to
 * be caused by the effect rather than by the mere shape of the draft.
 *
 * Registry-level tests run their host on a fixed-seed [SimulationController];
 * the one test that reads `GET /cell/{ref}` runs a [VirtualThreadScheduler]
 * host under an [InspectorServer] (the `InspectorTopologyTest` fixture).
 *
 * `[WKB2-30]` watch: wherever a test emits over a link the applier made while
 * that link was live, it also asserts the receiving cell got the value — a
 * tap-counted emission the live sink never received would be a message lost
 * at a link operation, and is to be left failing and filed, not worked around.
 */
class ApplyResidueTest {

    private var vtScheduler: VirtualThreadScheduler? = null
    private var server: InspectorServer? = null
    private var probe: HttpProbe? = null
    private val json = Json { ignoreUnknownKeys = false }

    @AfterEach
    fun tearDown() {
        probe?.close()
        server?.close()
        vtScheduler?.shutdown()
    }

    private class Fixture(val registry: LocationRegistry, val host: ManagedHost, val controller: SimulationController?) {
        private var now = 1_000L

        fun <C : Cell> live(cell: C): C {
            host.managementInlet.call.spawn(cell)
            return cell
        }

        fun applier(skipPrecheck: Boolean = false, beforeBoundaryLink: (Int) -> Unit = {}) = StagedApplier(
            hosts = mapOf(HOST to host),
            registry = registry,
            clock = { now++ },
            skipPrecheck = skipPrecheck,
            beforeBoundaryLink = beforeBoundaryLink,
        )

        /** Flushes a simulated host; a no-op for a real scheduler (callers await instead). */
        fun settle() {
            controller?.runToIdle()
        }
    }

    private fun simFixture(seed: Long): Fixture {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        return Fixture(registry, ManagedHost(scheduler = controller.scheduler(), registry = registry), controller)
    }

    private fun routedFixture(): Pair<Fixture, HttpProbe> {
        val registry = LocationRegistry()
        val hostRef = CellRef(UUID.randomUUID())
        val scheduler = VirtualThreadScheduler("ManagedHost-${hostRef.id}").also { vtScheduler = it }
        val host = ManagedHost(ref = hostRef, scheduler = scheduler, registry = registry)
        val started = InspectorServer(registry, mapOf(HOST to host), port = 0).startUnscheduled().also { server = it }
        val p = HttpProbe("http://localhost:${started.boundPort}").also { probe = it }
        return Fixture(registry, host, null) to p
    }

    private fun spec(vararg steps: GraphStep) = GraphSpec(steps.toList())

    /** A spawn step that remembers the cell its latest `create` built — STAGE's, since PRECHECK's cold build is call 1. */
    private class Captured<C : Cell>(private val build: (CellRef) -> C) {
        @Volatile
        var cell: C? = null

        fun step(handle: String) = SpawnStep(handle, CellFactory { ref -> build(ref).also { cell = it } })

        val staged: C get() = cell.shouldNotBeNull()
    }

    private fun outbound(handle: String, live: CellRef, livePort: String = "inlet") =
        BoundaryLink(live, livePort, handle, "outlet", Direction.OUTBOUND)

    private fun inbound(live: CellRef, handle: String) =
        BoundaryLink(live, "outlet", handle, "inlet", Direction.INBOUND)

    private fun detail(p: HttpProbe, ref: CellRef): JsonObject =
        json.parseToJsonElement(p.state("${InspectorServer.CELL_PATH}/${InspectorServer.encodeRef(ref)}")).jsonObject

    // ---- B3 clean ------------------------------------------------------------

    @Test
    fun `WKB2-25 an emission between two staged cells is not residue - unwound-clean`() {
        val f = simFixture(seed = 25)
        f.live(SinkCell())
        val refsBefore = f.registry.localRefs()
        val linksBefore = f.registry.all()
        val e = Captured(::EmitterCell)
        val k = Captured(::SinkCell)
        // STAGE's construction of "bad" (its factory's call 2) runs after the
        // connect step: it emits on the staged emitter, then fails the spawn.
        val calls = AtomicInteger()
        val bad = SpawnStep("bad", CellFactory { ref ->
            if (calls.incrementAndGet() == 2) {
                e.staged.emit("internal")
                throw IllegalStateException("injected")
            }
            SinkCell(ref)
        })

        val record = f.applier().apply(
            Draft(HOST, spec(e.step("e"), k.step("k"), ConnectStep("e", "outlet", "k", "inlet"), bad)),
            applyId = "r-25a", identity = "operator", baseTopologyVersion = 1,
        )
        f.settle()

        record.outcome shouldBe ApplyOutcome.UnwoundClean
        record.steps.getValue("bad") shouldBe StepOutcome.Failed("injected")
        // non-vacuous: the emission happened and crossed the staged-internal link
        k.staged.received shouldContainExactly listOf("internal")
        f.registry.localRefs() shouldBe refsBefore
        f.registry.all() shouldBe linksBefore
    }

    // ---- B3 residue and its inert control ---------------------------------------

    @Test
    fun `WKB2-25 an emission across a live boundary link is reported as exactly that edge, never unwound-clean`() {
        val (f, p) = routedFixture()
        val a = f.live(SinkCell())
        val r = f.live(RejectingInlet())
        awaitUntil("live cells published") { f.registry.localRefs().containsAll(listOf(a.ref, r.ref)) }
        val linksBefore = detail(p, a.ref).getValue("links")
        val e = Captured(::EmitterCell)
        var linkZeroId: String? = null

        val record = f.applier(skipPrecheck = true, beforeBoundaryLink = { index ->
            if (index == 1) {
                linkZeroId = f.registry.all().single { it.to.cell == a.ref }.id.toString()
                e.staged.emit("leak")
            }
        }).apply(
            Draft(HOST, spec(e.step("e")), boundary = listOf(outbound("e", a.ref), outbound("e", r.ref))),
            applyId = "r-25b", identity = "operator", baseTopologyVersion = 1,
        )

        val outcome = record.outcome.shouldBeInstanceOf<ApplyOutcome.UnwoundWithResidue>()
        val edge = outcome.residue.single().shouldBeInstanceOf<Residue.EmittedAcrossBoundary>().edge
        edge.id shouldBe linkZeroId.shouldNotBeNull()
        edge.from.ref shouldBe InspectorServer.encodeRef(e.staged.ref)
        edge.from.port shouldBe "outlet"
        edge.to.ref shouldBe InspectorServer.encodeRef(a.ref)
        edge.to.port shouldBe "inlet"
        // [WKB2-30]: the counted emission reached the live sink over the live link
        awaitUntil("live sink received the boundary emission") { a.received.contains("leak") }
        a.received shouldContainExactly listOf("leak")
        // the live sink's link census is back to its pre-apply value
        awaitUntil("live sink's inbound link retracted") { detail(p, a.ref).getValue("links") == linksBefore }
        f.registry.all().none { it.from.cell == e.staged.ref || it.to.cell == e.staged.ref } shouldBe true
    }

    @Test
    fun `WKB2-25 the same two boundary links with no emission unwind clean`() {
        val f = simFixture(seed = 251)
        val a = f.live(SinkCell())
        val r = f.live(RejectingInlet())
        val refsBefore = f.registry.localRefs()
        val linksBefore = f.registry.all()
        val e = Captured(::EmitterCell)
        var linkZeroLive = false

        val record = f.applier(skipPrecheck = true, beforeBoundaryLink = { index ->
            if (index == 1) linkZeroLive = f.registry.all().any { it.to.cell == a.ref }
        }).apply(
            Draft(HOST, spec(e.step("e")), boundary = listOf(outbound("e", a.ref), outbound("e", r.ref))),
            applyId = "r-25c", identity = "operator", baseTopologyVersion = 1,
        )
        f.settle()

        linkZeroLive shouldBe true // the boundary link existed; only the emission is missing
        record.outcome shouldBe ApplyOutcome.UnwoundClean
        a.received shouldBe emptyList()
        f.registry.localRefs() shouldBe refsBefore
        f.registry.all() shouldBe linksBefore
    }

    // ---- B4 (e1ojt-D11) -----------------------------------------------------------

    private fun ownedDraft(p: OwnedProducerCell, r: RejectingInlet, c: Captured<OwnedConsumerCell>, e: Captured<EmitterCell>) =
        Draft(
            HOST,
            spec(c.step("c"), e.step("e")),
            boundary = listOf(inbound(p.ref, "c"), outbound("e", r.ref)),
        )

    @Test
    fun `WKB2-25 a force-staged consumer that took an Owned payload over an inbound boundary link reports OwnedConsumed`() {
        val f = simFixture(seed = 26)
        val producer = f.live(OwnedProducerCell())
        val r = f.live(RejectingInlet())
        val refsBefore = f.registry.localRefs()
        val linksBefore = f.registry.all()
        val c = Captured(::OwnedConsumerCell)
        val e = Captured(::EmitterCell)

        val record = f.applier(skipPrecheck = true, beforeBoundaryLink = { index ->
            if (index == 1) producer.emit("owned-1")
        }).apply(ownedDraft(producer, r, c, e), applyId = "r-26a", identity = "operator", baseTopologyVersion = 1)
        f.settle()

        val outcome = record.outcome.shouldBeInstanceOf<ApplyOutcome.UnwoundWithResidue>()
        outcome.residue.size shouldBe 2
        val edge = outcome.residue[0].shouldBeInstanceOf<Residue.EmittedAcrossBoundary>().edge
        edge.from.ref shouldBe InspectorServer.encodeRef(producer.ref)
        edge.to.ref shouldBe InspectorServer.encodeRef(c.staged.ref)
        outcome.residue[1] shouldBe Residue.OwnedConsumed(InspectorServer.encodeRef(c.staged.ref), "inlet")
        // [WKB2-30]: the staged consumer did take the payload the tap counted
        c.staged.consumed shouldContainExactly listOf("owned-1")
        f.registry.localRefs() shouldBe refsBefore
        f.registry.all() shouldBe linksBefore
    }

    @Test
    fun `WKB2-26 the same draft un-forced is refused at precheck with OWNED_INTAKE in the plan`() {
        val f = simFixture(seed = 261)
        val producer = f.live(OwnedProducerCell())
        val r = f.live(RejectingInlet())
        val refsBefore = f.registry.localRefs()
        val c = Captured(::OwnedConsumerCell)
        val e = Captured(::EmitterCell)
        var boundaryAttempted = false

        val record = f.applier(beforeBoundaryLink = { boundaryAttempted = true })
            .apply(ownedDraft(producer, r, c, e), applyId = "r-26b", identity = "operator", baseTopologyVersion = 1)

        record.outcome shouldBe ApplyOutcome.RefusedAtPrecheck
        val plan = record.plan.shouldNotBeNull()
        val intake = plan.steps.single { it.key == "${producer.ref}.outlet->c.inlet" }
        intake.refusal.shouldNotBeNull().code shouldBe "OWNED_INTAKE"
        boundaryAttempted shouldBe false
        record.stagedRefs shouldBe emptyList()
        f.registry.localRefs() shouldBe refsBefore
    }

    private companion object {
        const val HOST = "h1"
    }
}
