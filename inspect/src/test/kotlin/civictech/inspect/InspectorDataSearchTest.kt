package civictech.inspect

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.LinkResult
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/**
 * M5-SEARCH — `GET /api/inspect/search?mode=data&q=`: find the cell holding a
 * record.
 *
 * The three things this mode has to be true about, and which these tests pin:
 *
 * 1. it **finds** the record, and says which graph and cell hold it;
 * 2. it **stops** — at the cell cap and at the time budget — and says so;
 * 3. it **leaves the graph alone**: no observation, no link, no attention, and
 *    nothing at all touched on a cell that is not hot.
 */
class InspectorDataSearchTest {

    private val json = Json { ignoreUnknownKeys = false }
    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private var server: InspectorServer? = null
    private lateinit var probe: HttpProbe

    @AfterEach
    fun tearDown() {
        server?.close()
    }

    // ------------------------------------------------------------- finding it

    @Test
    fun `a seeded record is found, with its graph, its ref and the matching value`() {
        spawn(refA)
        seed(refA, "alice", "bob")
        started(names = mapOf(refA to "people")).nameGraph(refA, "skillmatch")

        val result = search("alice")

        result.mode shouldBe "data"
        val hit = result.hits.single()
        hit.graph shouldBe "g-$A"
        hit.ref shouldBe InspectorServer.encodeRef(refA)
        hit.label shouldBe "alice"
        hit.detail shouldBe "skillmatch / people · SetCell — 1 record"
        result.cost shouldBe SearchCost(cellsQueried = 1, coldSkipped = 0)
    }

    /**
     * The question the mode exists to answer is "*which* cells hold it" — a
     * record that flowed downstream lives in more than one, and every holder is
     * a hit in its own right.
     */
    @Test
    fun `a record that propagated downstream is found in every cell holding it`() {
        spawn(refA)
        spawn(refB)
        link(refA, refB)
        seed(refA, "alice")
        awaitUntil("the delta reached the downstream cell") { holds(refB, 1) }
        started(names = mapOf(refA to "people", refB to "mirror"))

        val hits = search("alice").hits

        hits.map { it.ref } shouldContainExactly listOf(
            InspectorServer.encodeRef(refA),
            InspectorServer.encodeRef(refB),
        )
        // one component, so both hits open the same graph
        hits.map { it.graph }.distinct() shouldContainExactly listOf("g-$A")
        hits.last().detail shouldBe "g-$A / mirror · SetCell — 1 record"
    }

    /** Substring, case-insensitively — and one hit per *cell*, counting the records in it. */
    @Test
    fun `a substring matches several records in one cell and reports the count`() {
        spawn(refA)
        seed(refA, "alice", "alicia", "bob")
        started(names = mapOf(refA to "people"))

        val hit = search("ALIC").hits.single()

        // display order is by rendered value, so "alice" precedes "alicia"
        hit.label shouldBe "alice"
        hit.detail shouldBe "g-$A / people · SetCell — 2 records"
    }

    @Test
    fun `a record-shaped element matches on any of its columns`() {
        spawn(refA)
        seedRecords(refA, Skill("alice", "kotlin"), Skill("bob", "rust"))
        started(names = mapOf(refA to "candSkills"))

        // matches the `skill` column, not the key the row is ordered by
        val hit = search("rust").hits.single()

        hit.label shouldBe "rust"
        hit.detail shouldBe "g-$A / candSkills · SetCell — 1 record"
    }

    @Test
    fun `a query that matches nothing still reports what it cost to find out`() {
        spawn(refA)
        seed(refA, "alice")
        started()

        val result = search("nothing-like-this")

        result.hits.shouldBeEmpty()
        result.cost shouldBe SearchCost(cellsQueried = 1, coldSkipped = 0)
    }

    @Test
    fun `a blank query queries nothing at all`() {
        spawn(refA)
        seed(refA, "alice")
        started()

        val result = search("")

        result.hits.shouldBeEmpty()
        // the point: an empty submit must not spend a single host read
        result.cost shouldBe SearchCost(cellsQueried = 0, coldSkipped = 0)
    }

    @Test
    fun `name and problems modes still carry no cost object`() {
        spawn(refA)
        started()

        json.decodeFromString<SearchResult>(
            probe.state("${InspectorServer.SEARCH_PATH}?mode=name&q=SetCell"),
        ).cost shouldBe null
        json.decodeFromString<SearchResult>(
            probe.state("${InspectorServer.SEARCH_PATH}?mode=problems&q="),
        ).cost shouldBe null
    }

    // ------------------------------------------------------------- stopping

    @Test
    fun `the cell cap bounds the fan-out and the result says it was cut short`() {
        repeat(DataSearch.MAX_CELLS + 5) { index -> seed(spawn(CellRef(UUID.randomUUID())), "row-$index") }
        started()

        val result = search("row-")

        result.cost!!.cellsQueried shouldBe DataSearch.MAX_CELLS
        // the hits themselves are real; only the sweep was cut short
        result.hits.count { it.graph != DataSearch.NOTICE_GRAPH } shouldBe DataSearch.MAX_CELLS
        val notice = result.hits.last()
        notice.graph shouldBe DataSearch.NOTICE_GRAPH
        notice.ref shouldBe null
        notice.label shouldBe "Partial results"
        notice.detail shouldContain "stopped at the ${DataSearch.MAX_CELLS}-cell cap"
    }

    /**
     * The encoder's own row budget is the other way a search can be partial: a
     * cell with more rows than `ValueEncoder.MAX_ROWS` is only *read* that far,
     * so a record past the cut is genuinely unfindable and the result has to
     * say so rather than report a confident "no match".
     */
    @Test
    fun `a cell read past the encoder's row budget reports a partial read`() {
        spawn(refA)
        seedRecords(refA, *(0 until ValueEncoder.MAX_ROWS + 20).map { "row-$it" }.toTypedArray())
        started()

        val result = search("row-1")

        result.cost!!.cellsQueried shouldBe 1
        val notice = result.hits.last()
        notice.graph shouldBe DataSearch.NOTICE_GRAPH
        notice.label shouldBe "Partial results"
        notice.detail shouldContain "read only to the first ${ValueEncoder.MAX_ROWS} rows"
    }

    /**
     * The time budget, with a cell that is deliberately slow to snapshot. The
     * budget is injected rather than waited out: what is under test is that the
     * deadline is honored and reported, not that 2000 is the number.
     */
    @Test
    fun `a slow cell costs the search its budget, not the request`() {
        host.managementInlet.call.spawn(SlowCell(CellRef(UUID.randomUUID())))
        seed(spawn(refB), "alice")
        val serving = started()

        val search = DataSearch(
            registry = registry,
            components = serving::componentsNow,
            observed = { null },
            budgetMs = BUDGET_MS,
        )
        val startedAt = System.currentTimeMillis()
        val result = search.search("alice")
        val elapsed = System.currentTimeMillis() - startedAt

        // bounded by the injected budget, not by the cell's own long snapshot
        elapsed shouldBeLessThan SLOW_SNAPSHOT_MS
        // whichever order the two components sort in, the slow cell's read is
        // abandoned and the result says so
        val notice = result.hits.last()
        notice.graph shouldBe DataSearch.NOTICE_GRAPH
        notice.label shouldBe "Partial results"
        notice.detail shouldContain "did not answer in time"
    }

    // --------------------------------------------------- leaving it alone

    @Test
    fun `a suspended cell is skipped, counted as cold, and never read`() {
        spawn(refA)
        seed(refA, "alice")
        val serving = started()
        host.managementInlet.call.suspend(refA)
        awaitUntil("refA suspended") { host.isSuspended(refA) }

        val result = search("alice")

        result.hits.shouldBeEmpty()
        result.cost shouldBe SearchCost(cellsQueried = 0, coldSkipped = 1)
        // M1-EVAL's leak check: searching created no ObserveCell sink, so it
        // raised no attention on the cone it declined to read
        serving.observedRefs.shouldBeEmpty()
        registry.localRefs() shouldContainExactly setOf(refA)
    }

    /** The same leak check for the ordinary path: a *successful* search subscribes to nothing either. */
    @Test
    fun `searching subscribes to nothing and adds no cell or link`() {
        spawn(refA)
        spawn(refB)
        link(refA, refB)
        seed(refA, "alice")
        val serving = started()
        val refsBefore = registry.localRefs()
        val linksBefore = registry.all().size

        search("alice").hits.size shouldBeGreaterThan 0

        serving.observedRefs.shouldBeEmpty()
        registry.localRefs() shouldContainExactly refsBefore
        registry.all().size shouldBe linksBefore
    }

    /** A held ref (mid-migration) is parking its traffic by design — do not add to it. */
    @Test
    fun `a held cell is skipped and counted cold`() {
        spawn(refA)
        seed(refA, "alice")
        started()
        registry.hold(refA)

        val result = search("alice")

        result.hits.shouldBeEmpty()
        result.cost shouldBe SearchCost(cellsQueried = 0, coldSkipped = 1)

        registry.release(refA)
    }

    // -------------------------------------------------------------- fixtures

    private val refA = CellRef(UUID.fromString(A))
    private val refB = CellRef(UUID.fromString(B))

    private fun spawn(ref: CellRef): CellRef {
        host.managementInlet.call.spawn(SetCell<Any>(ref = ref))
        return ref
    }

    private fun link(from: CellRef, to: CellRef) {
        host.managementInlet.call.connect(from, "outlet", to, "deltaInlet") as LinkResult.Connected
    }

    private fun seed(ref: CellRef, vararg elements: String) = seedRecords(ref, *elements)

    private fun seedRecords(ref: CellRef, vararg elements: Any) {
        @Suppress("UNCHECKED_CAST")
        val api = HostedCellProxy.create(ref, registry, SetApi::class.java) as SetApi<Any>
        elements.forEach { api.inlet.call.add(it) }
        awaitUntil("$ref holds ${elements.size} elements") { holds(ref, elements.size) }
    }

    /** Reads through the very accessor the search uses — so the fixture proves the seam too. */
    private fun holds(ref: CellRef, elements: Int): Boolean {
        val snapshot = host.snapshotOf(ref).get()
        return ((snapshot as? Map<*, *>)?.get("adds") as? Map<*, *>)?.size == elements
    }

    private fun started(names: Map<CellRef, String> = emptyMap()): InspectorServer {
        val started = InspectorServer(registry, mapOf("test-host" to host), port = 0, cellNames = names).start()
        server = started
        probe = HttpProbe("http://localhost:${started.boundPort}")
        return started
    }

    private fun search(query: String): SearchResult =
        json.decodeFromString(
            probe.state("${InspectorServer.SEARCH_PATH}?mode=data&q=${java.net.URLEncoder.encode(query, "UTF-8")}"),
        )

    /** A record shape the encoder renders as a `$table` — one row per element, columns per property. */
    data class Skill(val candidate: String, val skill: String) : Serializable

    /**
     * A [Stateful] cell whose snapshot takes far longer than the search budget
     * — the "deliberately slow cell" the ticket asks for. It sleeps on the
     * host's own thread, which is exactly the stall the inspector's deadline
     * has to survive.
     *
     * The *first* snapshot returns immediately: `spawn` takes a checkpoint of
     * every [Stateful] cell on the management band, and a cell that stalled
     * there would never finish spawning at all.
     */
    class SlowCell(override val ref: CellRef) : Cell, Stateful {
        private val calls = java.util.concurrent.atomic.AtomicInteger()

        override fun snapshot(): Serializable {
            if (calls.getAndIncrement() > 0) Thread.sleep(SLOW_SNAPSHOT_MS)
            return HashMap<String, String>()
        }

        override fun restore(state: Serializable) = Unit
    }

    private companion object {
        const val A = "0a000000-0000-4000-8000-000000000000"
        const val B = "0b000000-0000-4000-8000-000000000000"

        /** Short enough to keep the suite quick, long enough that a 150 ms budget clearly beats it. */
        const val SLOW_SNAPSHOT_MS = 1_500L
        const val BUDGET_MS = 150L
    }
}
