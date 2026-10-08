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
import civictech.cell.graph.StepEvent
import civictech.cell.graph.StepResult
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.inspect.InspectorServer
import civictech.inspect.inspectorJson
import civictech.testkit.awaitUntil
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WKB2 F6 task 1 (`computenet-wczst.1`, wczst-D1/D2): the seams the write
 * plane's routes build on — [ApplyListener], [StagedApplier.stagedApplyOf],
 * [StagedApplier.plan], the record registered before the apply lock, and
 * [DraftDto]'s `host`/`despawns`.
 *
 * The fixture copies `StagedApplierTest`'s shape (a [SimulationController]
 * scheduler with a fixed seed; the cells of `FailureInjection.kt`) rather than
 * importing its private class. The applier awaits every management verb it
 * drives, so no bounded wait is needed except in the register-before-lock
 * test, which deliberately runs two applies on two threads.
 */
class StagedApplierListenerTest {

    private class Fixture(seed: Long) {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        private var now = 1_000L

        fun <C : Cell> live(cell: C): C {
            host.managementInlet.call.spawn(cell)
            return cell
        }

        fun applier(listener: ApplyListener = ApplyListener.None) = StagedApplier(
            hosts = mapOf(HOST to host),
            registry = registry,
            clock = { synchronized(this) { now++ } },
            listener = listener,
        )
    }

    /**
     * Records every listener call as one trace line. A staged ref is named by
     * its position in the applier's own `record(id).stagedRefs` — so a line
     * `staged:<handle>` is itself evidence the record already held the ref.
     */
    private class Recording(private val handles: List<String>) : ApplyListener {
        lateinit var applier: StagedApplier
        val trace = CopyOnWriteArrayList<String>()
        val done = CopyOnWriteArrayList<ApplyRecord>()

        override fun onPhase(record: ApplyRecord) {
            trace += "phase:${record.phase}"
        }

        override fun onStep(applyId: String, event: StepEvent) {
            val result = when (event.result) {
                is StepResult.Applied -> "applied"
                is StepResult.Rejected -> "failed"
            }
            trace += "step:${event.index}:${event.handle}:$result"
        }

        override fun onStaged(applyId: String, ref: CellRef) {
            val position = applier.record(applyId)?.stagedRefs?.indexOf(InspectorServer.encodeRef(ref)) ?: -1
            trace += "staged:${handles.getOrElse(position) { "NOT-IN-RECORD" }}"
        }

        override fun onDone(record: ApplyRecord) {
            done += record
            val type = inspectorJson.encodeToJsonElement(ApplyOutcome.serializer(), record.outcome!!)
                .jsonObject.getValue("type").jsonPrimitive.content
            trace += "done:$type"
        }
    }

    private fun spec(vararg steps: GraphStep) = GraphSpec(steps.toList())

    private fun emitter(handle: String) = SpawnStep(handle, CellFactory { ref -> EmitterCell(ref) })

    private fun sink(handle: String) = SpawnStep(handle, CellFactory { ref -> SinkCell(ref) })

    /** Fails in STAGE: call 1 is PRECHECK's cold construction (see [FailingFactory]). */
    private fun failingSink(handle: String) = SpawnStep(handle, FailingFactory(2) { ref -> SinkCell(ref) })

    private fun outbound(handle: String, live: CellRef) =
        BoundaryLink(live, "inlet", handle, "outlet", Direction.OUTBOUND)

    /** Two spawns, one internal connect, one OUTBOUND boundary into [live]. */
    private fun committable(live: CellRef) = Draft(
        HOST,
        spec(emitter("e"), sink("s"), ConnectStep("e", "outlet", "s", "inlet")),
        boundary = listOf(outbound("e", live)),
    )

    // ---- ApplyListener -------------------------------------------------------

    @Test
    fun `a committed apply announces PRECHECK, STAGE, each spawn staged before its step event, CUT_OVER, RETIRE, done once`() {
        val f = Fixture(seed = 61)
        val a = f.live(SinkCell())
        val listener = Recording(listOf("e", "s"))
        val applier = f.applier(listener).also { listener.applier = it }

        val record = applier.apply(committable(a.ref), applyId = "c-1", identity = "operator", baseTopologyVersion = 1)

        record.outcome shouldBe ApplyOutcome.Committed
        // Observed order: the recorder's spawnBound (onStaged) returns before
        // applyRemote reports that spawn's StepEvent (onStep), so staged:<h>
        // precedes step:<i>:<h>.
        listener.trace shouldContainExactly listOf(
            "phase:PRECHECK",
            "phase:STAGE",
            "staged:e",
            "step:0:e:applied",
            "staged:s",
            "step:1:s:applied",
            "step:2:e.outlet->s.inlet:applied",
            "phase:CUT_OVER",
            "phase:RETIRE",
            "done:committed",
        )
        listener.done.single().phase shouldBe ApplyPhase.RETIRE
    }

    @Test
    fun `a STAGE failure announces the failed step, UNWIND and unwound-clean exactly once`() {
        val f = Fixture(seed = 62)
        val listener = Recording(listOf("e", "s"))
        val applier = f.applier(listener).also { listener.applier = it }

        val record = applier.apply(
            Draft(HOST, spec(emitter("e"), failingSink("s"))),
            applyId = "c-2", identity = "operator", baseTopologyVersion = 1,
        )

        record.outcome shouldBe ApplyOutcome.UnwoundClean
        listener.trace shouldContainExactly listOf(
            "phase:PRECHECK",
            "phase:STAGE",
            "staged:e",
            "step:0:e:applied",
            "step:1:s:failed",
            "phase:UNWIND",
            "done:unwound-clean",
        )
        listener.done.single() shouldBe record
    }

    @Test
    fun `a precheck refusal announces PRECHECK and refused-at-precheck and nothing else`() {
        val f = Fixture(seed = 63)
        val listener = Recording(listOf("e"))
        val applier = f.applier(listener).also { listener.applier = it }

        val record = applier.apply(
            Draft(HOST, spec(emitter("e")), boundary = listOf(outbound("e", CellRef(UUID.randomUUID())))),
            applyId = "c-3", identity = "operator", baseTopologyVersion = 1,
        )

        record.outcome shouldBe ApplyOutcome.RefusedAtPrecheck
        listener.trace shouldContainExactly listOf("phase:PRECHECK", "done:refused-at-precheck")
    }

    // ---- stagedApplyOf -------------------------------------------------------

    @Test
    fun `stagedApplyOf names the in-flight apply for its staged refs only, and null once committed`() {
        val f = Fixture(seed = 64)
        val a = f.live(SinkCell())
        val applier = f.applier()
        var stagedAtPause: String? = "unset"
        var secondAtPause: String? = "unset"
        var liveAtPause: String? = "unset"
        lateinit var staged: List<CellRef>
        applier.afterStage = { rec ->
            staged = rec.stagedRefs.map { InspectorServer.decodeRef(it)!! }
            stagedAtPause = applier.stagedApplyOf(staged[0])
            secondAtPause = applier.stagedApplyOf(staged[1])
            liveAtPause = applier.stagedApplyOf(a.ref)
        }

        applier.stagedApplyOf(a.ref).shouldBeNull()
        val record = applier.apply(committable(a.ref), applyId = "s-1", identity = "operator", baseTopologyVersion = 1)

        record.outcome shouldBe ApplyOutcome.Committed
        stagedAtPause shouldBe "s-1"
        secondAtPause shouldBe "s-1"
        liveAtPause.shouldBeNull()
        staged.forEach { applier.stagedApplyOf(it).shouldBeNull() }
        applier.stagedApplyOf(a.ref).shouldBeNull()
    }

    @Test
    fun `stagedApplyOf answers null once the in-flight apply has entered RETIRE`() {
        val f = Fixture(seed = 68)
        val a = f.live(SinkCell())
        lateinit var applier: StagedApplier
        val atRetire = CopyOnWriteArrayList<String?>()
        val listener = object : ApplyListener {
            override fun onPhase(record: ApplyRecord) {
                if (record.phase == ApplyPhase.RETIRE) {
                    record.stagedRefs.mapTo(atRetire) { applier.stagedApplyOf(InspectorServer.decodeRef(it)!!) }
                }
            }
            override fun onStep(applyId: String, event: StepEvent) = Unit
            override fun onStaged(applyId: String, ref: CellRef) = Unit
            override fun onDone(record: ApplyRecord) = Unit
        }
        applier = f.applier(listener)

        applier.apply(committable(a.ref), applyId = "r-1", identity = "operator", baseTopologyVersion = 1)
            .outcome shouldBe ApplyOutcome.Committed

        atRetire shouldContainExactly listOf(null, null)
    }

    // ---- plan ----------------------------------------------------------------

    @Test
    fun `plan returns the plan apply records, stages nothing and mints no record`() {
        val f = Fixture(seed = 65)
        val a = f.live(SinkCell())
        val applier = f.applier()
        val refused = Draft(HOST, spec(emitter("e")), boundary = listOf(outbound("e", CellRef(UUID.randomUUID()))))
        val appliable = committable(a.ref)
        val refsBefore = f.registry.localRefs()
        val linksBefore = f.registry.all()

        val refusedPlan = applier.plan(refused)
        val appliablePlan = applier.plan(appliable)

        refusedPlan.appliable shouldBe false
        appliablePlan.appliable shouldBe true
        f.registry.localRefs() shouldBe refsBefore
        f.registry.all() shouldBe linksBefore
        applier.record("p-refused").shouldBeNull()
        applier.record("p-appliable").shouldBeNull()

        val refusedRecord = applier.apply(refused, applyId = "p-refused", identity = "operator", baseTopologyVersion = 1)
        val appliedRecord = applier.apply(appliable, applyId = "p-appliable", identity = "operator", baseTopologyVersion = 1)

        refusedPlan.steps.map { it.key } shouldContainExactly refusedRecord.plan.shouldNotBeNull().steps.map { it.key }
        refusedPlan shouldBe refusedRecord.plan
        appliablePlan shouldBe appliedRecord.plan
    }

    @Test
    fun `plan refuses the caller faults apply refuses`() {
        val f = Fixture(seed = 66)
        val applier = f.applier()

        shouldThrow<IllegalArgumentException> { applier.plan(Draft("nope", spec())) }
        shouldThrow<IllegalArgumentException> {
            PromotionRequest(incumbent = CellRef(UUID.randomUUID()))
        }
        val live = f.live(SinkCell()).ref
        shouldThrow<IllegalArgumentException> { applier.plan(Draft(HOST, spec(), despawns = listOf(live, live))) }
    }

    // ---- register before lock -----------------------------------------------

    @Test
    fun `a queued apply's record reads PRECHECK while another holds the lock, and a reused id is refused`() {
        val f = Fixture(seed = 67)
        val a = f.live(SinkCell())
        val applier = f.applier()
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        applier.afterStage = { rec ->
            if (rec.applyId == "A") {
                parked.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        val resultA = AtomicReference<Result<ApplyRecord>>()
        val resultB = AtomicReference<Result<ApplyRecord>>()

        val threadA = Thread {
            resultA.set(runCatching { applier.apply(committable(a.ref), "A", "operator", 1) })
        }.apply { start() }
        parked.await(30, TimeUnit.SECONDS) shouldBe true
        val threadB = Thread {
            resultB.set(runCatching { applier.apply(Draft(HOST, spec(sink("b"))), "B", "operator", 1) })
        }.apply { start() }

        try {
            awaitUntil("apply B's record while A is parked in STAGE", timeoutMs = 10_000) { applier.record("B") != null }
            applier.record("B").shouldNotBeNull().phase shouldBe ApplyPhase.PRECHECK
            applier.record("B").shouldNotBeNull().outcome.shouldBeNull()
            applier.record("A").shouldNotBeNull().phase shouldBe ApplyPhase.STAGE
            resultA.get().shouldBeNull()
        } finally {
            release.countDown()
        }
        threadA.join(30_000)
        threadB.join(30_000)

        resultA.get().shouldNotBeNull().getOrThrow().outcome shouldBe ApplyOutcome.Committed
        resultB.get().shouldNotBeNull().getOrThrow().outcome shouldBe ApplyOutcome.Committed
        shouldThrow<IllegalArgumentException> { applier.apply(Draft(HOST, spec()), "A", "operator", 1) }
        applier.record("A").shouldNotBeNull().outcome shouldBe ApplyOutcome.Committed
    }

    // ---- DraftDto ------------------------------------------------------------

    @Test
    fun `DraftDto decodes host and despawns with defaults, and compile ignores both`() {
        val live = InspectorServer.encodeRef(CellRef(UUID.randomUUID()))
        val edges = """[{"from":{"handle":"x","port":"outlet"},"to":{"ref":"$live","port":"inlet"}}]"""

        val bare = inspectorJson.decodeFromString<DraftDto>("""{"nodes":[],"edges":$edges}""")
        val full = inspectorJson.decodeFromString<DraftDto>(
            """{"nodes":[],"edges":$edges,"host":"h","despawns":["$live"]}""",
        )
        val empty = inspectorJson.decodeFromString<DraftDto>("""{"nodes":[],"edges":[]}""")

        empty.host.shouldBeNull()
        empty.despawns.shouldBeEmpty()
        bare.host.shouldBeNull()
        bare.despawns.shouldBeEmpty()
        full.host shouldBe "h"
        full.despawns shouldContainExactly listOf(live)
        DraftCompiler.compile(full) shouldBe DraftCompiler.compile(bare)
        DraftCompiler.compile(empty.copy(host = "h", despawns = listOf(live))) shouldBe DraftCompiler.compile(empty)
    }

    private companion object {
        const val HOST = "h1"
    }
}
