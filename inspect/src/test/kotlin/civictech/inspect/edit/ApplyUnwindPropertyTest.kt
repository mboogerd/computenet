package civictech.inspect.edit

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.SetCell
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
import civictech.cell.link.LinkResult
import civictech.inspect.InspectorServer
import civictech.inspect.TopologySnapshot
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID

/**
 * WKB2 F3 task 2 (`computenet-e1ojt.2`): `[WKB2-19]` and `[WKB2-60]`.
 *
 * - **Identity after unwind** (B2, e1ojt-D9) is asserted where an operator
 *   sees it: the inspector's read routes. `GET /topology`, `GET /graphs` and
 *   every pre-existing cell's `GET /cell/{ref}` are read before and after a
 *   STAGE failure and compared as parsed JSON with `seq` removed — `seq` is
 *   the delta sequence the model advances on every publish/link event, the
 *   one field that legitimately moves.
 *   **`GET /errors` is deliberately excluded**: a STAGE step the host refuses
 *   inside `spawn` is dead-lettered there (`[15-APPLY-01]`,
 *   `ManagedHost.spawnBound`'s catch, `GraphSpecRemoteApplyTest`), so the
 *   route legitimately changes after such a failed apply; the record cites the
 *   same failure as a `Failed` step. **Observed limit of the planned
 *   cross-check:** this test's injection is a *factory* throw, and
 *   `spawnBound` calls `factory.create` before its dead-lettering `try`, so
 *   no row is written for it (awaited 10 s over `/errors` during
 *   computenet-e1ojt.2: none arrived) — the row-exists assertion the bead
 *   proposed would be false here, and is not made. The record's
 *   `Failed("injected")` step is the citation that is asserted.
 * - **The seeded apply/unwind property** (B18, e1ojt-D10) is a fixed-seed
 *   `java.util.Random` loop over seeds 1..[SEEDS] (`kotest-property` is not on
 *   this module's classpath). Each seed builds a fresh fixed-seed
 *   [SimulationController] host, so every seed is reproducible on its own.
 *   Per-seed cost is one small host plus at most six spawns — a few ms; the
 *   whole loop stays well under a second locally. A seed that ever fails is
 *   added as its own `regression seed <n>` test and never replaced
 *   (`[WKB2-60]`; AGENTS.md "Preserve deterministic simulation/generative
 *   tests"). None has failed so far.
 */
class ApplyUnwindPropertyTest {

    private val json = Json { ignoreUnknownKeys = false }
    private var vtScheduler: VirtualThreadScheduler? = null
    private var server: InspectorServer? = null
    private var probe: HttpProbe? = null

    @AfterEach
    fun tearDown() {
        probe?.close()
        server?.close()
        vtScheduler?.shutdown()
    }

    // ---- [WKB2-19] — B2 at route level -----------------------------------------

    /** A route's body as parsed JSON, `seq` dropped at the top level (the only field allowed to move). */
    private fun HttpProbe.read(path: String): JsonElement {
        val parsed = json.parseToJsonElement(state(path))
        return if (parsed is JsonObject) JsonObject(parsed.jsonObject - "seq") else parsed
    }

    @Test
    fun `WKB2-19 identity after unwind - every inspector read route decodes equal to its pre-apply read`() {
        val registry = LocationRegistry()
        val hostRef = CellRef(UUID.randomUUID())
        val scheduler = VirtualThreadScheduler("ManagedHost-${hostRef.id}").also { vtScheduler = it }
        val host = ManagedHost(ref = hostRef, scheduler = scheduler, registry = registry)
        val a = SetCell<String>()
        val b = SetCell<String>()
        host.managementInlet.call.spawn(a)
        host.managementInlet.call.spawn(b)
        host.managementInlet.call.connect(a.ref, "outlet", b.ref, "deltaInlet").shouldBeInstanceOf<LinkResult.Connected>()
        val started = InspectorServer(registry, mapOf(HOST to host), port = 0).startUnscheduled().also { server = it }
        val p = HttpProbe("http://localhost:${started.boundPort}").also { probe = it }
        awaitUntil("both live cells published") { registry.localRefs().containsAll(listOf(a.ref, b.ref)) }

        val cellPaths = listOf(a.ref, b.ref).map { "${InspectorServer.CELL_PATH}/${InspectorServer.encodeRef(it)}" }
        val paths = listOf(InspectorServer.TOPOLOGY_PATH, InspectorServer.GRAPHS_PATH) + cellPaths
        val before = paths.associateWith { p.read(it) }
        val nodesBefore = json.decodeFromString<TopologySnapshot>(p.state(InspectorServer.TOPOLOGY_PATH)).nodes.map { it.ref }.toSet()
        val refsBefore = registry.localRefs()
        val linksBefore = registry.all()
        var stagedSeen = false
        registry.onLocalPublish { if (it !in refsBefore) stagedSeen = true }

        var now = 1_000L
        val record = StagedApplier(mapOf(HOST to host), registry, clock = { now++ }).apply(
            Draft(
                HOST,
                GraphSpec(
                    listOf(
                        SpawnStep("s1", CellFactory { ref -> SetCell<String>(ref) }),
                        SpawnStep("s2", CellFactory { ref -> SetCell<String>(ref) }),
                        ConnectStep("s1", "outlet", "s2", "deltaInlet"),
                        // call 1 is PRECHECK's cold build; call 2 is STAGE's (see FailingFactory)
                        SpawnStep("s3", FailingFactory(2) { ref -> SetCell<String>(ref) }),
                    ),
                ),
            ),
            applyId = "u-19", identity = "operator", baseTopologyVersion = 1,
        )

        record.outcome shouldBe ApplyOutcome.UnwoundClean
        record.steps.getValue("s3") shouldBe StepOutcome.Failed("injected")
        record.stagedRefs.size shouldBe 2
        stagedSeen shouldBe true // non-vacuous: the staged cells did reach the live registry
        registry.localRefs() shouldBe refsBefore
        registry.all() shouldBe linksBefore
        // the model's hooks fire synchronously on the registry thread; this is a bounded guard
        awaitUntil("topology node set back to its pre-apply value") {
            json.decodeFromString<TopologySnapshot>(p.state(InspectorServer.TOPOLOGY_PATH)).nodes.map { it.ref }.toSet() == nodesBefore
        }
        paths.forEach { path -> (path to p.read(path)) shouldBe (path to before.getValue(path)) }

        // GET /errors is excluded from identity — see the class KDoc for why, and
        // for why this particular injection leaves no row there to assert on.
    }

    // ---- [WKB2-60] — B18 seeded property -----------------------------------------

    private enum class Kind { SET, EMITTER, SINK }

    /** One generated draft, the cells STAGE built for it, and what the draw decided. */
    private class Case(val seed: Long) {
        val rnd = Random(seed)
        val kinds = List(1 + rnd.nextInt(6)) { Kind.entries[rnd.nextInt(Kind.entries.size)] }
        val built = arrayOfNulls<Cell>(kinds.size)
        val connects: List<Pair<Int, Int>>
        val failing: Int
        val boundaryFrom: List<Int>

        init {
            // acyclic by construction (lower index to higher) and compatible ports only
            val compatible = kinds.indices.flatMap { i ->
                (i + 1 until kinds.size).filter { j -> portsFor(kinds[i], kinds[j]) != null }.map { j -> i to j }
            }.toMutableList()
            val wanted = rnd.nextInt(6)
            connects = buildList {
                while (size < wanted && compatible.isNotEmpty()) add(compatible.removeAt(rnd.nextInt(compatible.size)))
            }
            failing = rnd.nextInt(kinds.size + 1) // == kinds.size means none
            val emitters = kinds.indices.filter { kinds[it] == Kind.EMITTER }.toMutableList()
            val wantedBoundary = minOf(rnd.nextInt(3), emitters.size)
            boundaryFrom = List(wantedBoundary) { emitters.removeAt(rnd.nextInt(emitters.size)) }
        }

        fun handle(i: Int) = "c$i"

        fun build(i: Int, ref: CellRef): Cell = when (kinds[i]) {
            Kind.SET -> SetCell<String>(ref)
            Kind.EMITTER -> EmitterCell(ref)
            Kind.SINK -> SinkCell(ref)
        }.also { built[i] = it }

        fun draft(liveSink: CellRef): Draft {
            val spawns = kinds.indices.map { i ->
                val factory = if (i == failing) FailingFactory(2) { ref -> build(i, ref) } else CellFactory { ref -> build(i, ref) }
                SpawnStep(handle(i), factory)
            }
            val links = connects.map { (i, j) ->
                val (out, inl) = portsFor(kinds[i], kinds[j])!!
                ConnectStep(handle(i), out, handle(j), inl)
            }
            val boundary = boundaryFrom.map { i -> BoundaryLink(liveSink, "inlet", handle(i), "outlet", Direction.OUTBOUND) }
            return Draft(HOST, GraphSpec(spawns + links), boundary = boundary)
        }

        override fun toString() =
            "seed=$seed kinds=$kinds connects=$connects failing=$failing boundaryFrom=$boundaryFrom"

        private companion object {
            fun portsFor(from: Kind, to: Kind): Pair<String, String>? = when {
                from == Kind.EMITTER && to == Kind.SINK -> "outlet" to "inlet"
                from == Kind.SET && to == Kind.SET -> "outlet" to "deltaInlet"
                else -> null
            }
        }
    }

    private val commitChain = listOf(ApplyPhase.PRECHECK, ApplyPhase.STAGE, ApplyPhase.CUT_OVER, ApplyPhase.RETIRE)
    private val unwindChain = listOf(ApplyPhase.PRECHECK, ApplyPhase.STAGE, ApplyPhase.CUT_OVER, ApplyPhase.UNWIND)

    /** True when [observed] occurs, in order, within [chain]. */
    private fun isSubsequence(observed: List<ApplyPhase>, chain: List<ApplyPhase>): Boolean {
        var at = 0
        observed.forEach { phase ->
            while (at < chain.size && chain[at] != phase) at++
            if (at == chain.size) return false
            at++
        }
        return true
    }

    /**
     * One seed of B18. The phase chain is sampled at every observable point
     * (each factory call, each registry publish/unpublish/link/unlink, each
     * test hook) and must be an in-order subsequence of D8's commit chain
     * PRECHECK→STAGE→CUT_OVER→RETIRE or its unwind chain ending UNWIND, with
     * the terminal phase matching the outcome. A CUT_OVER with no boundary
     * link has no observable point, so sampling may miss it — hence
     * subsequence, not equality.
     */
    private fun checkSeed(seed: Long) {
        val case = Case(seed)
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val liveSink = SinkCell().also { host.managementInlet.call.spawn(it) }
        controller.runToIdle()
        val refsBefore = registry.localRefs()
        val linksBefore = registry.all()
        val applyId = "seed-$seed"
        val phases = mutableListOf<ApplyPhase>()
        lateinit var applier: StagedApplier
        fun sample() {
            val phase = applier.record(applyId)?.phase ?: return
            if (phases.lastOrNull() != phase) phases += phase
        }
        registry.onLocalPublish { sample() }
        registry.onLocalUnpublish { sample() }
        registry.onLocalTopology({ sample() }, { sample() })
        var now = 1_000L
        applier = StagedApplier(
            mapOf(HOST to host), registry, clock = { now++ },
            afterStage = { sample() },
            beforeBoundaryLink = { sample() },
        )
        val draft = case.draft(liveSink.ref)
        // sample during PRECHECK too: wrap each spawn's factory
        val sampled = draft.copy(
            spec = GraphSpec(
                draft.spec.steps.map { step ->
                    if (step is SpawnStep) step.copy(factory = CellFactory { ref -> sample(); step.factory.create(ref) }) else step
                },
            ),
        )

        val record = applier.apply(sampled, applyId = applyId, identity = "operator", baseTopologyVersion = 1)
        sample()
        controller.runToIdle()

        withClue("$case record=$record phases=$phases") {
            if (case.failing == case.kinds.size) {
                record.outcome shouldBe ApplyOutcome.Committed
                phases.last() shouldBe ApplyPhase.RETIRE
                isSubsequence(phases, commitChain) shouldBe true
                val refsAfter = registry.localRefs()
                refsAfter.shouldContainAll(refsBefore)
                refsAfter.size - refsBefore.size shouldBe case.kinds.size
                checkNoLoss(case, liveSink, controller)
            } else {
                // the only injected failure is a STAGE spawn, before any boundary link
                // exists, and nothing emits — so residue here would be a false report
                record.outcome shouldBe ApplyOutcome.UnwoundClean
                phases.last() shouldBe ApplyPhase.UNWIND
                isSubsequence(phases, unwindChain) shouldBe true
                registry.localRefs() shouldBe refsBefore
                registry.all() shouldBe linksBefore
            }
        }
    }

    /**
     * `[WKB2-30]` watch on a committed seed: every staged emitter emits once,
     * and every sink — staged or the live boundary sink — receives exactly one
     * value per emitter link into it. A shortfall is a message lost on a link
     * the applier made.
     */
    private fun checkNoLoss(case: Case, liveSink: SinkCell, controller: SimulationController) {
        val emitters = case.kinds.indices.filter { case.kinds[it] == Kind.EMITTER }
        emitters.forEach { i -> (case.built[i] as EmitterCell).emit("from-c$i") }
        controller.runToIdle()
        case.kinds.indices.filter { case.kinds[it] == Kind.SINK }.forEach { j ->
            val expected = case.connects.filter { it.second == j }.map { "from-c${it.first}" }.sorted()
            withClue("staged sink c$j") { (case.built[j] as SinkCell).received.sorted() shouldBe expected }
        }
        withClue("live sink") { liveSink.received.sorted() shouldBe case.boundaryFrom.map { "from-c$it" }.sorted() }
    }

    @Test
    fun `WKB2-60 seeded apply-unwind property over seeds 1 to 50`() {
        val committed = mutableListOf<Long>()
        val unwound = mutableListOf<Long>()
        (1L..SEEDS).forEach { seed ->
            checkSeed(seed)
            if (Case(seed).failing == Case(seed).kinds.size) committed += seed else unwound += seed
        }
        // the generator reaches both arms, so neither assertion block is vacuous
        committed.shouldNotBeEmpty()
        unwound.shouldNotBeEmpty()
        (1L..SEEDS).map(::Case).filter { it.boundaryFrom.isNotEmpty() && it.failing == it.kinds.size }.shouldNotBeEmpty()
        (1L..SEEDS).map(::Case).filter { it.connects.isNotEmpty() && it.failing < it.kinds.size }.shouldNotBeEmpty()
    }

    private companion object {
        const val HOST = "h1"
        const val SEEDS = 50L
    }
}
