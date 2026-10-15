package civictech.inspect.edit

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.inspect.Event
import civictech.inspect.InspectorServer
import civictech.inspect.inspectorJson
import civictech.testkit.SseTap
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WKB2 F6 task 3 (`computenet-wczst.3`) — the five write-plane routes over
 * HTTP, end to end: route order (wczst-D8), the gate first on every route,
 * precheck without effect, `202`/`422` (wczst-D3), the read and abort shapes
 * (wczst-D4), B10's legible partially-applied graph (wczst-D5/D6,
 * `[WKB2-09]`, `[WKB2-38]`, `[WKB2-40]`) and `[WKB2-41]`'s residue read back
 * after completion (wczst-D9).
 *
 * **Catalogue stand-ins (wczst-D9).** The three `test.WritePlaneRoutesTest.*`
 * entries all name `descriptorFqn = "civictech.cell.data.SetCell"`:
 * `Catalogue.register` only requires that fqn to be present in the contract
 * registry, and `emitter` / `sink` build `FailureInjection.kt`'s
 * descriptor-less cells. The palette metadata those entries advertise is a
 * stand-in and is **not under test** here — what is under test is what the
 * routes do with the cells the entries build. The catalogue is a process-wide
 * singleton shared by every `:inspect` test in the JVM, so the entries are
 * registered per test and removed in [tearDown].
 *
 * Every wait is bounded ([awaitUntil], [SseTap.awaitAtLeast]) and keyed on an
 * observable — a terminal record or an `apply.*` frame — never a sleep.
 */
class WritePlaneRoutesTest {

    private val registry = LocationRegistry()

    /** Owned so [tearDown] can stop it (computenet-4vh; see `InspectorErrorsTest`). */
    private val hostRef = CellRef(UUID.randomUUID())
    private val hostScheduler = VirtualThreadScheduler("ManagedHost-${hostRef.id}")
    private val host = ManagedHost(ref = hostRef, scheduler = hostScheduler, registry = registry)

    private val servers = mutableListOf<InspectorServer>()
    private val schedulers = mutableListOf(hostScheduler)
    private val taps = mutableListOf<SseTap<Frame>>()

    /** Released in [tearDown] too, so a failed test never leaves an apply parked forever. */
    private val latches = mutableListOf<CountDownLatch>()

    /** Every [EmitterCell] the `emitter` entry built, in order — the last one is the staged one. */
    private val emitters = CopyOnWriteArrayList<EmitterCell>()

    private lateinit var server: InspectorServer

    @BeforeEach
    fun setUp() {
        register(SET) { ref -> SetCell<Any>(ref = ref) }
        register(EMITTER) { ref -> EmitterCell(ref).also { emitters += it } }
        register(SINK) { ref -> SinkCell(ref) }
        server = started(mapOf("h" to host))
    }

    @AfterEach
    fun tearDown() {
        latches.forEach { while (it.count > 0) it.countDown() }
        taps.forEach { it.close() }
        servers.forEach { it.close() }
        schedulers.forEach { it.shutdown() }
        listOf(SET, EMITTER, SINK).forEach(Catalogue::unregister)
    }

    // ---- 1. route order (wczst-D8) ------------------------------------------------

    @Test
    fun `applies and apply-precheck both resolve on one server`() {
        val applies = send("GET", InspectorServer.APPLIES_PATH)
        applies.statusCode() shouldBe 200
        applies.body() shouldBe """{"entries":[]}"""

        val precheck = send("POST", PRECHECK, body = """{"nodes":[{"handle":"s","catalogueId":"$SET"}]}""")
        precheck.statusCode() shouldBe 200
        body(precheck)["appliable"]!!.jsonPrimitive.content shouldBe "true"
    }

    @Test
    fun `a non-GET on applies and a GET on apply-precheck are 404s naming the expected shape`() {
        val postApplies = send("POST", InspectorServer.APPLIES_PATH, body = "{}")
        postApplies.statusCode() shouldBe 404
        reasonOf(postApplies) shouldBe "expected GET /applies"

        val getPrecheck = send("GET", PRECHECK)
        getPrecheck.statusCode() shouldBe 404
        reasonOf(getPrecheck) shouldBe WritePlaneRoutes.APPLY_SHAPE
    }

    // ---- 2. the gate first, on every route ----------------------------------------

    @Test
    fun `every write-plane route refuses a missing or wrong capability before anything else`() {
        val routes = listOf(
            "GET" to InspectorServer.APPLIES_PATH,
            "GET" to "${InspectorServer.APPLY_PATH}/x",
            "POST" to "${InspectorServer.APPLY_PATH}/x/abort",
            "POST" to InspectorServer.APPLY_PATH,
            "POST" to PRECHECK,
        )
        routes.forEach { (method, path) ->
            // a malformed body: had the route read it, the answer would be `400 malformed body`
            val missing = sendTo(server.boundPort, method, path, body = "not json{")
            missing.statusCode() shouldBe 400
            reasonOf(missing) shouldBe "missing required header: X-Inspector-Write"

            val wrong = sendTo(server.boundPort, method, path, body = "not json{", writeHeader = "not-the-capability")
            wrong.statusCode() shouldBe 403
            reasonOf(wrong) shouldBe "capability rejected"
        }
    }

    // ---- 3. precheck has no side effect ---------------------------------------------

    @Test
    fun `precheck answers the plan and changes nothing`() {
        val a = live(SetCell<Any>())
        val seqBefore = topology()["seq"]!!.jsonPrimitive.long
        val refsBefore = registry.localRefs()

        val response = send("POST", PRECHECK, body = oneNodeDraft(a.ref))

        response.statusCode() shouldBe 200
        val plan = inspectorJson.decodeFromString(PlanDto.serializer(), response.body())
        plan.appliable shouldBe true
        plan.steps.map { it.key } shouldContainExactly listOf("s", "s.outlet->${InspectorServer.encodeRef(a.ref)}.deltaInlet")
        topology()["seq"]!!.jsonPrimitive.long shouldBe seqBefore
        registry.localRefs() shouldBe refsBefore
        send("GET", InspectorServer.APPLIES_PATH).body() shouldBe """{"entries":[]}"""
    }

    // ---- 4. precheck refusal shapes ---------------------------------------------------

    @Test
    fun `an unknown ref and an unknown catalogue id are 200 not-appliable plans`() {
        val unknownRef = send("POST", PRECHECK, body = oneNodeDraft(CellRef(UUID.randomUUID())))
        unknownRef.statusCode() shouldBe 200
        val refPlan = inspectorJson.decodeFromString(PlanDto.serializer(), unknownRef.body())
        refPlan.appliable shouldBe false
        refPlan.steps.mapNotNull { it.refusal?.code } shouldContainExactly listOf("UNKNOWN_REF")

        val unknownId = send("POST", PRECHECK, body = """{"nodes":[{"handle":"s","catalogueId":"test.WritePlaneRoutesTest.nope"}]}""")
        unknownId.statusCode() shouldBe 200
        val idPlan = inspectorJson.decodeFromString(PlanDto.serializer(), unknownId.body())
        idPlan.appliable shouldBe false
        idPlan.steps.single().refusal!!.code shouldBe "UNKNOWN_CATALOGUE_ID"
    }

    @Test
    fun `a DTO-level fault is 400 with the compiler's reason, and a non-JSON body is 400 malformed body`() {
        val a = live(SetCell<Any>())
        val both = send(
            "POST", PRECHECK,
            body = """{"nodes":[{"handle":"s","catalogueId":"$SET"}],
                "edges":[{"from":{"handle":"s","port":"outlet"},"to":{"handle":"s","ref":"${enc(a.ref)}","port":"deltaInlet"}}]}""",
        )
        both.statusCode() shouldBe 400
        reasonOf(both) shouldBe "edge 0 target names both handle 's' and ref '${enc(a.ref)}'; exactly one is allowed"

        val notJson = send("POST", PRECHECK, body = "not json")
        notJson.statusCode() shouldBe 400
        reasonOf(notJson) shouldBe "malformed body"
    }

    /** The wczst.1 review's AMENDS: `StagedApplier.plan`'s `IllegalArgumentException` is a caller fault — 400, never 500. */
    @Test
    fun `a despawn listed twice is a 400 on precheck and on apply`() {
        val a = live(SetCell<Any>())
        val draft = """{"nodes":[{"handle":"s","catalogueId":"$SET"}],"despawns":["${enc(a.ref)}","${enc(a.ref)}"]}"""

        val precheck = send("POST", PRECHECK, body = draft)
        precheck.statusCode() shouldBe 400
        reasonOf(precheck) shouldBe "a despawn target is listed more than once"

        val apply = send("POST", InspectorServer.APPLY_PATH, body = """{"draft":$draft,"baseSeq":1}""")
        apply.statusCode() shouldBe 400
        reasonOf(apply) shouldBe "a despawn target is listed more than once"
        send("GET", InspectorServer.APPLIES_PATH).body() shouldBe """{"entries":[]}"""
    }

    // ---- 5. host resolution (wczst-D1) --------------------------------------------------

    @Test
    fun `host defaults to the sole host, and an unknown host or a garbage despawn is 400`() {
        send("POST", PRECHECK, body = """{"nodes":[{"handle":"s","catalogueId":"$SET"}]}""").statusCode() shouldBe 200

        val unknown = send("POST", PRECHECK, body = """{"nodes":[{"handle":"s","catalogueId":"$SET"}],"host":"nope"}""")
        unknown.statusCode() shouldBe 400
        reasonOf(unknown) shouldBe "unknown host 'nope'"

        val garbage = send("POST", PRECHECK, body = """{"nodes":[{"handle":"s","catalogueId":"$SET"}],"despawns":["garbage"]}""")
        garbage.statusCode() shouldBe 400
        reasonOf(garbage) shouldBe "despawns[0]: 'garbage' is not an encoded cell ref (\"<uuid>:<instanceId>\")"
    }

    @Test
    fun `with two hosts an omitted host is 400`() {
        val secondRef = CellRef(UUID.randomUUID())
        val secondScheduler = VirtualThreadScheduler("ManagedHost-${secondRef.id}").also { schedulers += it }
        val second = ManagedHost(ref = secondRef, scheduler = secondScheduler, registry = registry)
        val two = started(mapOf("h" to host, "h2" to second))

        val response = sendTo(
            two.boundPort, "POST", PRECHECK,
            body = """{"nodes":[{"handle":"s","catalogueId":"$SET"}]}""", writeHeader = CAPABILITY,
        )

        response.statusCode() shouldBe 400
        reasonOf(response) shouldBe "draft.host is required: this inspector has 2 hosts"
    }

    // ---- 6. apply, committed -------------------------------------------------------------

    @Test
    fun `an appliable draft is 202, readable at once, and commits into the ring and the topology`() {
        val a = live(SetCell<Any>())

        val accepted = send("POST", InspectorServer.APPLY_PATH, body = """{"draft":${oneNodeDraft(a.ref)},"baseSeq":7}""")

        accepted.statusCode() shouldBe 202
        val id = body(accepted)["applyId"]!!.jsonPrimitive.content
        val first = send("GET", "${InspectorServer.APPLY_PATH}/$id")
        first.statusCode() shouldBe 200
        val initial = record(first)
        initial.baseTopologyVersion shouldBe 7
        initial.identity shouldBe "capability-holder"

        val done = awaitTerminal(id)
        done.outcome shouldBe ApplyOutcome.Committed
        done.steps.values.toSet() shouldBe setOf(StepOutcome.Applied)
        done.stagedRefs.size shouldBe 1
        done.completedAtMs.shouldNotBeNull()
        done.submittedDraft shouldBe Json.parseToJsonElement(oneNodeDraft(a.ref))

        awaitUntil("ring holds the terminal record") { entries().size == 1 }
        entries().single()["applyId"]!!.jsonPrimitive.content shouldBe id

        val staged = done.stagedRefs.single()
        awaitUntil("the committed cell and its edge are in the topology") {
            val topo = topology()
            nodeOf(topo, staged) != null && edges(topo).any { edgeFrom(it) == staged && edgeTo(it) == enc(a.ref) }
        }
        nodeOf(topology(), staged)!!["staged"] shouldBe JsonNull
    }

    // ---- 7. refused at precheck is 422, not a record ------------------------------------

    @Test
    fun `a not-appliable draft is 422 with the plan and leaves no record`() {
        val refsBefore = registry.localRefs()

        val response = send(
            "POST", InspectorServer.APPLY_PATH,
            body = """{"draft":${oneNodeDraft(CellRef(UUID.randomUUID()))},"baseSeq":1}""",
        )

        response.statusCode() shouldBe 422
        val refused = inspectorJson.decodeFromString(RefusedAtPrecheckDto.serializer(), response.body())
        refused.reason shouldBe "refused-at-precheck"
        refused.plan.appliable shouldBe false
        send("GET", InspectorServer.APPLIES_PATH).body() shouldBe """{"entries":[]}"""
        registry.localRefs() shouldBe refsBefore
    }

    // ---- 8. baseSeq required, confirmation ignored --------------------------------------

    @Test
    fun `baseSeq is required and a confirmation is accepted and ignored`() {
        val draft = """{"nodes":[{"handle":"s","catalogueId":"$SET"}]}"""

        val noBase = send("POST", InspectorServer.APPLY_PATH, body = """{"draft":$draft}""")
        noBase.statusCode() shouldBe 400
        reasonOf(noBase) shouldBe "malformed body"

        val confirmed = send("POST", InspectorServer.APPLY_PATH, body = """{"draft":$draft,"baseSeq":3,"confirmation":"x"}""")
        confirmed.statusCode() shouldBe 202
        awaitTerminal(body(confirmed)["applyId"]!!.jsonPrimitive.content).outcome shouldBe ApplyOutcome.Committed
    }

    // ---- 9. B10: a partially-applied graph is legible -----------------------------------

    @Test
    fun `B10 a staged apply is marked in topology, counted as staged in graphs, and narrated on events`() {
        val live = live(SetCell<Any>())
        awaitUntil("live cell in the view") { nodeOf(topology(), enc(live.ref)) != null }
        val tap = listen()
        val (reached, release) = parkAfterStage()

        val accepted = send("POST", InspectorServer.APPLY_PATH, body = """{"draft":$TWO_NODE_DRAFT,"baseSeq":1}""")
        accepted.statusCode() shouldBe 202
        val id = body(accepted)["applyId"]!!.jsonPrimitive.content
        reached.await(30, TimeUnit.SECONDS) shouldBe true

        val parked = record(send("GET", "${InspectorServer.APPLY_PATH}/$id"))
        parked.phase shouldBe ApplyPhase.STAGE
        val stagedRefs = parked.stagedRefs
        stagedRefs.size shouldBe 2
        val stepCount = parked.steps.size // x, y and the internal link
        awaitUntil("every STAGE step narrated") { tap.frames().count { it.kind == Event.APPLY_STEP } == stepCount }
        awaitUntil("each staged ref's latest node frame carries the mark") {
            stagedRefs.all { ref -> lastNodeFrame(tap, ref)?.let(::markOf) == id }
        }

        // GET /topology: exactly the staged refs are marked, with this apply's id
        val topo = topology()
        stagedRefs.forEach { ref -> markOf(nodeOf(topo, ref)!!) shouldBe id }
        nodes(topo).filter { it["ref"]!!.jsonPrimitive.content !in stagedRefs }.forEach { it["staged"] shouldBe JsonNull }

        // GET /graphs: the staged component counts under staged, the live one is unchanged
        val graphs = graphs()
        val stagedCard = graphs.single { it["staged"]!!.jsonPrimitive.content.toInt() > 0 }
        stagedCard["cells"]!!.jsonPrimitive.content shouldBe "0"
        stagedCard["staged"]!!.jsonPrimitive.content shouldBe "2"
        val liveCard = graphs.single { it["id"]!!.jsonPrimitive.content == "g-${live.ref.id}" }
        liveCard["cells"]!!.jsonPrimitive.content shouldBe "1"
        liveCard["staged"]!!.jsonPrimitive.content shouldBe "0"

        // the tap so far, in order: PRECHECK, STAGE, then every step with index 0..n-1, all applied
        val applyFrames = tap.frames().filter { it.kind.startsWith("apply.") }
        applyFrames.take(2).map { it.kind to it.payload["phase"]!!.jsonPrimitive.content } shouldContainExactly
            listOf(Event.APPLY_PHASE to "PRECHECK", Event.APPLY_PHASE to "STAGE")
        val steps = applyFrames.drop(2)
        steps.map { it.kind }.toSet() shouldBe setOf(Event.APPLY_STEP)
        steps.map { it.payload["index"]!!.jsonPrimitive.content.toInt() } shouldContainExactly (0 until stepCount).toList()
        steps.map { it.payload["result"]!!.jsonObject["type"]!!.jsonPrimitive.content }.toSet() shouldBe setOf("applied")
        applyFrames.all { it.payload["applyId"]!!.jsonPrimitive.content == id } shouldBe true
        // the restamp follows the publish hook's unmarked `added` for the same ref
        stagedRefs.forEach { ref ->
            val nodeFrames = nodeFrames(tap, ref)
            nodeFrames.first().let(::markOf) shouldBe null
            nodeFrames.last().let(::markOf) shouldBe id
        }

        release.countDown()
        val done = awaitDone(tap, id)
        done.payload["outcome"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "committed"

        val phases = tap.frames().filter { it.kind == Event.APPLY_PHASE }.map { it.payload["phase"]!!.jsonPrimitive.content }
        phases shouldContainExactly listOf("PRECHECK", "STAGE", "CUT_OVER", "RETIRE")
        val retireSeq = tap.frames().single { it.kind == Event.APPLY_PHASE && it.payload["phase"]!!.jsonPrimitive.content == "RETIRE" }.seq
        stagedRefs.forEach { ref ->
            val last = lastNodeFrame(tap, ref)!!
            last.payload["op"]!!.jsonPrimitive.content shouldBe Event.ADDED
            markOf(last) shouldBe null
            (last.seq > retireSeq) shouldBe true
            (last.seq < done.seq) shouldBe true
        }
        nodes(topology()).forEach { it["staged"] shouldBe JsonNull }
        val seqs = tap.frames().map { it.seq }
        seqs.zipWithNext().all { (a, b) -> b > a } shouldBe true
    }

    // ---- 10. abort ------------------------------------------------------------------------

    @Test
    fun `abort is 404 for an unknown id and 409 for a committed apply`() {
        val unknown = send("POST", "${InspectorServer.APPLY_PATH}/nope/abort")
        unknown.statusCode() shouldBe 404
        reasonOf(unknown) shouldBe "unknown apply: nope"
        val unknownRead = send("GET", "${InspectorServer.APPLY_PATH}/nope")
        unknownRead.statusCode() shouldBe 404
        reasonOf(unknownRead) shouldBe "unknown apply: nope"

        val accepted = send("POST", InspectorServer.APPLY_PATH, body = """{"draft":$TWO_NODE_DRAFT,"baseSeq":1}""")
        val id = body(accepted)["applyId"]!!.jsonPrimitive.content
        awaitTerminal(id).outcome shouldBe ApplyOutcome.Committed

        val late = send("POST", "${InspectorServer.APPLY_PATH}/$id/abort")
        late.statusCode() shouldBe 409
        reasonOf(late) shouldBe "apply $id is not in STAGE"
    }

    @Test
    fun `abort of a STAGE-parked apply is 202 with the record, then unwinds clean`() {
        val tap = listen()
        val (reached, release) = parkAfterStage()
        val accepted = send("POST", InspectorServer.APPLY_PATH, body = """{"draft":$TWO_NODE_DRAFT,"baseSeq":1}""")
        val id = body(accepted)["applyId"]!!.jsonPrimitive.content
        reached.await(30, TimeUnit.SECONDS) shouldBe true

        val aborted = send("POST", "${InspectorServer.APPLY_PATH}/$id/abort")

        aborted.statusCode() shouldBe 202
        val atAbort = record(aborted)
        atAbort.phase shouldBe ApplyPhase.STAGE
        release.countDown()
        awaitDone(tap, id).payload["outcome"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "unwound-clean"
        val done = awaitTerminal(id)
        done.outcome shouldBe ApplyOutcome.UnwoundClean
        awaitUntil("staged refs gone from the topology") { atAbort.stagedRefs.all { nodeOf(topology(), it) == null } }
        awaitUntil("ring holds the record") { entries().any { it["applyId"]!!.jsonPrimitive.content == id } }
    }

    // ---- 11. [WKB2-41]: residue is retrievable after completion ---------------------------

    /**
     * The B3-shaped control, through the routes: a staged emitter links out to
     * two live sinks; before link 1 the emitter emits across link 0 and the
     * second sink is despawned, so link 1 cannot connect at CUT_OVER and the
     * apply unwinds with the emission as residue. PRECHECK ran against a live
     * `d`, so the failure is CUT_OVER-only, as `[WKB2-41]` needs.
     */
    @Test
    fun `WKB2-41 an unwound-with-residue apply returns its residue after completion`() {
        val a = live(SinkCell())
        val d = live(SinkCell())
        val tap = listen()
        server.stagedApplier.beforeBoundaryLink = { index ->
            if (index == 1) {
                emitters.last().emit("leak")
                awaitUntil("the live sink received the boundary emission") { a.received.contains("leak") }
                host.managementInlet.call.despawn(d.ref)
                awaitUntil("second boundary target despawned") { d.ref !in registry.localRefs() }
            }
        }
        val draft = """{"nodes":[{"handle":"e","catalogueId":"$EMITTER"}],"edges":[
            {"from":{"handle":"e","port":"outlet"},"to":{"ref":"${enc(a.ref)}","port":"inlet"}},
            {"from":{"handle":"e","port":"outlet"},"to":{"ref":"${enc(d.ref)}","port":"inlet"}}]}"""

        val accepted = send("POST", InspectorServer.APPLY_PATH, body = """{"draft":$draft,"baseSeq":1}""")
        accepted.statusCode() shouldBe 202
        val id = body(accepted)["applyId"]!!.jsonPrimitive.content
        val done = awaitDone(tap, id)

        done.payload["outcome"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "unwound-with-residue"
        val readBack = send("GET", "${InspectorServer.APPLY_PATH}/$id")
        readBack.statusCode() shouldBe 200
        val record = record(readBack)
        record.completedAtMs.shouldNotBeNull()
        val outcome = record.outcome.shouldBeInstanceOf<ApplyOutcome.UnwoundWithResidue>()
        val edge = outcome.residue.single().shouldBeInstanceOf<Residue.EmittedAcrossBoundary>().edge
        edge.to.ref shouldBe enc(a.ref)
        edge.from.port shouldBe "outlet"
        // the CUT_OVER failure is link 1's own: exactly one step failed, and it is the second boundary link
        // (observed: connecting to the despawned target answers "Target cell not found")
        record.steps.filterValues { it is StepOutcome.Failed }.keys shouldBe setOf("e.outlet->${InspectorServer.encodeRef(d.ref)}.inlet")
        // one shape for the event and the record (wczst-D5)
        done.payload["outcome"] shouldBe body(readBack)["outcome"]
        awaitUntil("ring holds the record") { entries().any { it["applyId"]!!.jsonPrimitive.content == id } }
    }

    // ---- fixtures --------------------------------------------------------------------------

    private fun register(id: String, build: (CellRef) -> Cell) {
        Catalogue.unregister(id) // a previous test's failed tearDown must not wedge this one
        Catalogue.register(CatalogueEntry(id, SET_FQN, ParamSchema(emptyList()), EntryBuilder { _, ref -> build(ref) }))
    }

    private fun started(hosts: Map<String, ManagedHost>): InspectorServer =
        InspectorServer(registry, hosts, port = 0, writePlane = WritePlane.Enabled(Capability(CAPABILITY)))
            .startUnscheduled()
            .also { servers += it }

    private fun <C : Cell> live(cell: C): C {
        host.managementInlet.call.spawn(cell)
        awaitUntil("live cell published") { cell.ref in registry.localRefs() }
        return cell
    }

    /** Installs a pause after STAGE on the server's applier: `first` counts down on arrival, `second` releases it. */
    private fun parkAfterStage(): Pair<CountDownLatch, CountDownLatch> {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1).also { latches += it }
        server.stagedApplier.afterStage = {
            reached.countDown()
            release.await(60, TimeUnit.SECONDS)
        }
        return reached to release
    }

    private fun send(method: String, path: String, body: String = ""): HttpResponse<String> =
        sendTo(server.boundPort, method, path, body, writeHeader = CAPABILITY)

    private fun body(response: HttpResponse<String>): JsonObject = Json.parseToJsonElement(response.body()).jsonObject

    private fun record(response: HttpResponse<String>): ApplyRecord =
        inspectorJson.decodeFromString(ApplyRecord.serializer(), response.body())

    private fun awaitTerminal(id: String): ApplyRecord {
        var terminal: ApplyRecord? = null
        awaitUntil("apply $id reaches a terminal outcome") {
            terminal = record(send("GET", "${InspectorServer.APPLY_PATH}/$id")).takeIf { it.outcome != null }
            terminal != null
        }
        return terminal!!
    }

    private fun entries(): List<JsonObject> =
        body(send("GET", InspectorServer.APPLIES_PATH))["entries"]!!.jsonArray.map { it.jsonObject }

    private fun topology(): JsonObject =
        Json.parseToJsonElement(sendTo(server.boundPort, "GET", InspectorServer.TOPOLOGY_PATH).body()).jsonObject

    private fun graphs(): List<JsonObject> =
        Json.parseToJsonElement(sendTo(server.boundPort, "GET", InspectorServer.GRAPHS_PATH).body())
            .jsonObject["graphs"]!!.jsonArray.map { it.jsonObject }

    private fun nodes(topology: JsonObject): List<JsonObject> = topology["nodes"]!!.jsonArray.map { it.jsonObject }

    private fun edges(topology: JsonObject): List<JsonObject> = topology["edges"]!!.jsonArray.map { it.jsonObject }

    private fun nodeOf(topology: JsonObject, ref: String): JsonObject? =
        nodes(topology).firstOrNull { it["ref"]!!.jsonPrimitive.content == ref }

    private fun edgeFrom(edge: JsonObject) = edge["from"]!!.jsonObject["ref"]!!.jsonPrimitive.content

    private fun edgeTo(edge: JsonObject) = edge["to"]!!.jsonObject["ref"]!!.jsonPrimitive.content

    /** A node object's (or a `topology.node` frame's node's) `staged.applyId`, or null when unmarked. */
    private fun markOf(node: JsonObject): String? =
        (node["staged"] as? JsonObject)?.get("applyId")?.jsonPrimitive?.content

    private fun markOf(frame: Frame): String? = markOf(frame.payload["node"]!!.jsonObject)

    private fun nodeFrames(tap: SseTap<Frame>, ref: String): List<Frame> = tap.frames().filter {
        it.kind == Event.TOPOLOGY_NODE && (it.payload["node"] as? JsonObject)?.get("ref")?.jsonPrimitive?.content == ref
    }

    private fun lastNodeFrame(tap: SseTap<Frame>, ref: String): Frame? = nodeFrames(tap, ref).lastOrNull()

    private fun awaitDone(tap: SseTap<Frame>, id: String): Frame {
        awaitUntil("apply.done for $id") { doneFrame(tap, id) != null }
        return doneFrame(tap, id)!!
    }

    private fun doneFrame(tap: SseTap<Frame>, id: String): Frame? = tap.frames().singleOrNull {
        it.kind == Event.APPLY_DONE && it.payload["applyId"]!!.jsonPrimitive.content == id
    }

    private fun listen(): SseTap<Frame> {
        val opened = SseTap("http://localhost:${server.boundPort}${InspectorServer.EVENTS_PATH}", ::frame)
        taps += opened
        awaitUntil("sse client attached", timeoutMs = 5_000) { server.attachedClients > 0 }
        return opened
    }

    /** One SSE envelope, parsed. */
    private data class Frame(val seq: Long, val kind: String, val payload: JsonObject)

    private fun frame(data: String): Frame {
        val event = Json.parseToJsonElement(data).jsonObject
        return Frame(event["seq"]!!.jsonPrimitive.long, event["kind"]!!.jsonPrimitive.content, event["payload"]!!.jsonObject)
    }

    private companion object {
        const val CAPABILITY = "test-capability"
        const val PRECHECK = "${InspectorServer.APPLY_PATH}/precheck"
        const val SET_FQN = "civictech.cell.data.SetCell"
        const val SET = "test.WritePlaneRoutesTest.set"
        const val EMITTER = "test.WritePlaneRoutesTest.emitter"
        const val SINK = "test.WritePlaneRoutesTest.sink"

        /** Two staged set entries and one internal edge; no boundary link. */
        const val TWO_NODE_DRAFT = """{"nodes":[{"handle":"x","catalogueId":"$SET"},{"handle":"y","catalogueId":"$SET"}],""" +
            """"edges":[{"from":{"handle":"x","port":"outlet"},"to":{"handle":"y","port":"deltaInlet"}}]}"""

        fun enc(ref: CellRef) = InspectorServer.encodeRef(ref)

        /** `{s: set}` with one outbound boundary edge `s.outlet -> <live>.deltaInlet`. */
        fun oneNodeDraft(live: CellRef): String =
            """{"nodes":[{"handle":"s","catalogueId":"$SET"}],""" +
                """"edges":[{"from":{"handle":"s","port":"outlet"},"to":{"ref":"${enc(live)}","port":"deltaInlet"}}]}"""
    }
}
