package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.LinkResult
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * M4 — `GET /api/inspect/search`: the navigator's three modes. `name` and
 * `problems` are answered from metadata the inspector already holds, so
 * searching subscribes to nothing (P6); `data` is M5-SEARCH's and says so with
 * a 501 rather than a plausible-looking empty result.
 */
class InspectorSearchTest {

    private val json = Json { ignoreUnknownKeys = false }
    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private var server: InspectorServer? = null
    private lateinit var probe: HttpProbe

    @AfterEach
    fun tearDown() {
        server?.close()
    }

    // ------------------------------------------------------------ mode=name

    @Test
    fun `a graph name matches, case-insensitively, and the hit carries no ref`() {
        twoGraphs()
        started().nameGraph(refA, "skillmatch")

        val hits = search("name", "SKILL").hits

        hits.size shouldBe 1
        hits.single().graph shouldBe "g-$A"
        hits.single().ref shouldBe null
        hits.single().label shouldBe "skillmatch"
    }

    @Test
    fun `a cell name matches and its hit carries both the graph and the ref`() {
        twoGraphs()
        started(names = mapOf(refA to "candSkills", refB to "jobSkills"))

        val hits = search("name", "jobs").hits

        hits.single().graph shouldBe "g-$A"
        hits.single().ref shouldBe InspectorServer.encodeRef(refB)
        hits.single().label shouldBe "jobSkills"
        hits.single().detail shouldBe "civictech.cell.data.SetCell"
    }

    @Test
    fun `a cell type matches too, across every graph that holds one`() {
        twoGraphs()
        started()

        val hits = search("name", "SetCell").hits

        // all four cells are SetCells, two per component
        hits.map { it.graph } shouldContainExactly listOf("g-$A", "g-$A", "g-$C", "g-$C")
        hits.all { it.ref != null } shouldBe true
    }

    @Test
    fun `an unnamed graph still matches on its generated id`() {
        twoGraphs()
        started()

        val hits = search("name", "g-$C").hits

        hits.first().graph shouldBe "g-$C"
        // no name to show: the navigator renders the id
        hits.first().label shouldBe "g-$C"
        hits.first().ref shouldBe null
    }

    @Test
    fun `a blank query matches nothing rather than everything`() {
        twoGraphs()
        started()

        search("name", "").hits.shouldBeEmpty()
        // whitespace only, escaped: a query that trims to nothing is still nothing
        search("name", "%20%20").hits.shouldBeEmpty()
    }

    @Test
    fun `name is the default mode`() {
        twoGraphs()
        started()

        val body = json.decodeFromString<SearchResult>(probe.state("${InspectorServer.SEARCH_PATH}?q=SetCell"))

        body.mode shouldBe "name"
        body.hits.size shouldBe 4
        // the cost model belongs to data mode, which does not exist yet
        body.cost shouldBe null
    }

    // -------------------------------------------------------- mode=problems

    @Test
    fun `problems lists only graphs with a nonzero counter, dead letters first`() {
        twoGraphs()
        val serving = started()

        // graph C: one parked invocation. graph A: two dead letters — which
        // must outrank it however many parked messages C accumulates
        park(refC)
        repeat(2) { deadLetter(refA) }
        awaitUntil("dead letters captured") { serving.errorSnapshot().deadLetters.size == 2 }
        serving.pollErrorsNow()

        val hits = search("problems", "").hits

        hits.map { it.graph } shouldContainExactly listOf("g-$A", "g-$C")
        hits.first().detail shouldBe "2 dead"
        hits.last().detail shouldBe "1 parked"
        // a whole-graph hit, like a graph name hit
        hits.all { it.ref == null } shouldBe true

        release(refC)
    }

    /**
     * The hit `detail` the navigator renders verbatim. "dead" and "parked" are
     * adjectives and read the same at any count; "restart" is a noun and does
     * not, which the first cut got wrong ("1 restarts").
     */
    @Test
    fun `a problems detail lists its nonzero counters, and counts restarts in English`() {
        Graphs.describe(GraphHealth(deadLetters = 2, parked = 0, restarts = 0)) shouldBe "2 dead"
        Graphs.describe(GraphHealth(deadLetters = 0, parked = 1, restarts = 0)) shouldBe "1 parked"
        Graphs.describe(GraphHealth(deadLetters = 0, parked = 0, restarts = 1)) shouldBe "1 restart"
        Graphs.describe(GraphHealth(deadLetters = 0, parked = 0, restarts = 3)) shouldBe "3 restarts"
        Graphs.describe(GraphHealth(deadLetters = 1, parked = 11, restarts = 1)) shouldBe "1 dead · 11 parked · 1 restart"
    }

    @Test
    fun `a healthy process has no problems`() {
        twoGraphs()
        started()

        search("problems", "").hits.shouldBeEmpty()
    }

    // ------------------------------------------------------------ mode=data

    /**
     * M5-SEARCH replaced M4's 501 with a real, bounded content search — the
     * behaviour of which lives in `InspectorDataSearchTest`. What this test
     * still owns is the *routing*: `mode=data` is now a 200 carrying the
     * data-mode cost object, next to its two metadata-only siblings.
     */
    @Test
    fun `data search answers 200 with a cost object`() {
        twoGraphs()
        started()

        val response = probe.get("${InspectorServer.SEARCH_PATH}?mode=data&q=anything")

        response.statusCode() shouldBe 200
        val body = json.decodeFromString<SearchResult>(response.body())
        body.mode shouldBe "data"
        // four empty SetCells: read, matched nothing, and the price is reported
        body.cost shouldBe SearchCost(cellsQueried = 4, coldSkipped = 0)
    }

    @Test
    fun `an unrecognized mode is a 400, not a silent name search`() {
        twoGraphs()
        started()

        probe.get("${InspectorServer.SEARCH_PATH}?mode=vibes&q=x").statusCode() shouldBe 400
    }

    @Test
    fun `a percent-escaped query survives the wire`() {
        twoGraphs()
        started(names = mapOf(refA to "cand skills"))

        search("name", "cand%20sk").hits.single().label shouldBe "cand skills"
    }

    // -------------------------------------------------------------- fixtures

    // fixed uuids, so "lexicographically-min member" is a fact the assertions
    // can state rather than recompute: A < B < C < D
    private val refA = CellRef(UUID.fromString(A))
    private val refB = CellRef(UUID.fromString(B))
    private val refC = CellRef(UUID.fromString(C))
    private val refD = CellRef(UUID.fromString(D))

    /** Two disjoint two-cell components, `g-$A` (refA→refB) and `g-$C` (refC→refD). */
    private fun twoGraphs() {
        pair(refA, refB)
        pair(refC, refD)
    }

    private fun pair(from: CellRef, to: CellRef) {
        spawn(from)
        spawn(to)
        host.managementInlet.call.connect(from, "outlet", to, "deltaInlet") as LinkResult.Connected
    }

    private fun spawn(ref: CellRef) = host.managementInlet.call.spawn(SetCell<String>(ref = ref))

    /** Starts the server against whatever is already spawned; call after building the graph. */
    private fun started(names: Map<CellRef, String> = emptyMap()): InspectorServer {
        val started = InspectorServer(registry, mapOf("test-host" to host), port = 0, cellNames = names).start()
        server = started
        probe = HttpProbe("http://localhost:${started.boundPort}")
        return started
    }

    private fun search(mode: String, query: String): SearchResult =
        json.decodeFromString(probe.state("${InspectorServer.SEARCH_PATH}?mode=$mode&q=$query"))

    private fun park(ref: CellRef) {
        registry.hold(ref)
        @Suppress("UNCHECKED_CAST")
        val api = HostedCellProxy.create(ref, registry, SetApi::class.java) as SetApi<String>
        api.inlet.call.add("x")
        awaitUntil("one invocation parked on $ref") { registry.parkedFor(ref).size == 1 }
    }

    private fun release(ref: CellRef) {
        registry.release(ref)
        awaitUntil("parked traffic drained") { registry.parkedFor(ref).isEmpty() }
    }

    /**
     * A dead letter attributed to [ref]: the data-path "unknown port" drop,
     * which is the one branch that carries a `HostedPortInvocation` and so the
     * one whose row a component can be scoped by (`routerInlet.route` throws
     * before any invocation exists and dead-letters host-wide instead).
     */
    private fun deadLetter(ref: CellRef) = host.enqueueHostedInvocation(
        HostedPortInvocation(
            cellRef = ref,
            portName = "nope",
            type = HostedPortInvocation.Type.PORT_API,
            invocation = Invocation.of(PROVIDE, arrayOf("x")),
        ),
    )

    private companion object {
        const val A = "0a000000-0000-4000-8000-000000000000"
        const val B = "0b000000-0000-4000-8000-000000000000"
        const val C = "0c000000-0000-4000-8000-000000000000"
        const val D = "0d000000-0000-4000-8000-000000000000"

        val PROVIDE = Consumer::class.java.methods.first { it.name == "provide" }
    }
}
