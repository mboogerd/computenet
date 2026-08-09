package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.Owned
import civictech.cell.Timestamp
import civictech.cell.data.CounterApi
import civictech.cell.data.CounterCell
import civictech.cell.data.ListApi
import civictech.cell.data.ListCell
import civictech.cell.data.MapApi
import civictech.cell.data.MapCell
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.lookup
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V1C-BE — `GET /api/inspect/cell/{ref}/state?cursor=&limit=`: the paged state
 * read, end to end over the wire.
 *
 * The four things this endpoint has to be true about, and which these tests pin:
 *
 * 1. it **bounds the read**, not merely the render — a cell answers `limit`
 *    entries per request and says where the next page resumes;
 * 2. its **cursor is honest** — server-minted, one id per page, `ref`-bound,
 *    expiring, and a 410 rather than a silent restart for every way one can go
 *    stale (which is the whole reason to have a table instead of echoing the
 *    kernel's own token back through a client);
 * 3. it says **where the bytes came from and how much to believe** —
 *    `provenance` for a suspended cell and a drained host's checkpoint,
 *    `walkStable` for a walk whose fold moved under it, `unreadable` for each
 *    nothing the kernel decided;
 * 4. and it stays **read-only** (P6): a full multi-page walk installs no link,
 *    spawns no sink, attaches no tap and wakes nothing.
 */
class InspectorPagedStateTest {

    private val json = Json { ignoreUnknownKeys = false }
    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private var server: InspectorServer? = null
    private lateinit var probe: HttpProbe

    @AfterEach
    fun tearDown() {
        server?.close()
    }

    // ------------------------------------------------------------ the pages

    @Test
    fun `a cell with more entries than the limit answers one bounded page`() {
        val cell = set("a", "b", "c", "d", "e")
        started()

        val state = state(cell.ref, limit = 3)

        state.kind shouldBe CellState.PAGE
        state.provenance shouldBe CellState.LIVE
        // a wave position is not a tag frontier, and a paged read has no wave
        state.frontier shouldBe null
        state.staleMs shouldBe 0
        val page = state.page!!
        page.limit shouldBe 3
        page.entries shouldBe 3
        page.cursor shouldNotBe null
        // exactly those three entries, rendered
        elementsOf(state) shouldContainExactly listOf("a", "b", "c")
    }

    @Test
    fun `echoing the cursor walks the whole cell, disjointly, and then stops`() {
        val cell = set("a", "b", "c", "d", "e")
        started()

        val walk = walk(cell.ref, limit = 2)

        walk.map { it.page!!.entries } shouldContainExactly listOf(2, 2, 1)
        // the union over a quiescent fold is exactly the whole state, and no
        // entry was returned twice
        walk.flatMap { elementsOf(it) } shouldContainExactly listOf("a", "b", "c", "d", "e")
        walk.dropLast(1).forEach { it.page!!.cursor shouldNotBe null }
        walk.last().page!!.cursor shouldBe null
    }

    @Test
    fun `limit is clamped to the ceiling and the applied value is reported back`() {
        val cell = set("a", "b")
        started()

        state(cell.ref, limit = InspectorServer.PAGE_LIMIT_MAX * 10).page!!.limit shouldBe
            InspectorServer.PAGE_LIMIT_MAX
        // absent, it is the default rather than the cell's whole size
        state(cell.ref).page!!.limit shouldBe InspectorServer.PAGE_LIMIT_DEFAULT
    }

    @Test
    fun `a malformed limit is a 400, not a silently substituted default`() {
        val cell = set("a")
        started()

        probe.get(statePath(cell.ref) + "?limit=vibes").statusCode() shouldBe 400
        probe.get(statePath(cell.ref) + "?limit=0").statusCode() shouldBe 400
        probe.get(statePath(cell.ref) + "?limit=-4").statusCode() shouldBe 400
        // and the refusal cost nothing: the walk table is untouched
        probe.get(statePath(cell.ref) + "?limit=2").statusCode() shouldBe 200
    }

    // ----------------------------------------------------------- the cursor

    @Test
    fun `an unknown cursor is a 410, and a fresh walk after it succeeds`() {
        val cell = set("a", "b", "c")
        started()

        probe.get(statePath(cell.ref) + "?cursor=p-nope&limit=2").statusCode() shouldBe 410

        // the client's remedy: drop the cursor, restart the walk
        state(cell.ref, limit = 2).page!!.entries shouldBe 2
    }

    /**
     * One id per page. A re-sent cursor answers 410 (visible) rather than
     * silently re-serving or skipping a page (invisible) — the class of lie this
     * whole vertical exists to remove.
     */
    @Test
    fun `a cursor already consumed by a previous request is a 410`() {
        val cell = set("a", "b", "c", "d")
        started()
        val cursor = state(cell.ref, limit = 2).page!!.cursor!!

        probe.get(statePath(cell.ref) + "?cursor=$cursor").statusCode() shouldBe 200
        probe.get(statePath(cell.ref) + "?cursor=$cursor").statusCode() shouldBe 410
    }

    @Test
    fun `a cursor minted for another cell is a 410, never a page of the wrong cell`() {
        val one = set("a", "b", "c")
        val two = set("x", "y", "z")
        started()
        val cursor = state(one.ref, limit = 1).page!!.cursor!!

        probe.get(statePath(two.ref) + "?cursor=$cursor").statusCode() shouldBe 410
    }

    /** Driven by the inspector's injected clock, not by sleeping out a minute. */
    @Test
    fun `a cursor past its ttl is a 410`() {
        val cell = set("a", "b", "c", "d")
        val now = AtomicLong(1_000_000L)
        val serving = started()
        serving.inspectorClock = now::get
        val cursor = state(cell.ref, limit = 2).page!!.cursor!!

        // one tick short of the deadline: still resumable
        now.addAndGet(InspectorServer.CURSOR_TTL_MS - 1)
        probe.get(statePath(cell.ref) + "?cursor=$cursor").statusCode() shouldBe 200

        val next = state(cell.ref, limit = 2).page!!.cursor!!
        now.addAndGet(InspectorServer.CURSOR_TTL_MS)
        probe.get(statePath(cell.ref) + "?cursor=$next").statusCode() shouldBe 410
    }

    // -------------------------------------------------------- walk stability

    @Test
    fun `a walk over a quiescent fold closes stable`() {
        val cell = set("a", "b", "c", "d", "e")
        started()

        val walk = walk(cell.ref, limit = 2)

        // page 1 carries the exact opening stamp, so it compares with itself
        walk.first().page!!.walkStable shouldBe true
        // the intermediate page carries only the opening stamp and says so: the
        // verdict is not available yet, and reporting `true` there would be the
        // dishonest answer this ticket's C8 correction removed
        walk[1].page!!.walkStable shouldBe null
        walk[1].page!!.caveats.contains(StatePageView.STALE_FRONTIER) shouldBe true
        // and the closing page's exact stamp equals the opening one
        walk.last().page!!.walkStable shouldBe true
    }

    @Test
    fun `a fold that changes mid-walk closes smeared, with the walk still whole`() {
        val cell = set("a", "b", "c", "d", "e")
        started()

        val first = state(cell.ref, limit = 2)
        first.page!!.walkStable shouldBe true
        add(cell, "f")

        val walk = listOf(first) + resume(cell.ref, first, limit = 2)

        // the closing verdict is a proof the fold moved, not a guess
        walk.last().page!!.walkStable shouldBe false
        // and the walk still completed, whole, with no entry returned twice —
        // a smear is never a tear
        walk.last().page!!.cursor shouldBe null
        val seen = walk.flatMap { elementsOf(it) }
        seen shouldContainExactly listOf("a", "b", "c", "d", "e")
        seen.toSet().size shouldBe seen.size
    }

    /**
     * The caveat the kernel declares for a family with no element identity
     * (`ListCell`), forwarded rather than inferred: "no entry twice in one walk"
     * and "every surviving entry appears" both weaken to best-effort there, and
     * a client is told so instead of being left to assume the strong contract.
     */
    @Test
    fun `a positional cursor's caveat reaches the wire`() {
        val list = ListCell<String>().also { host.managementInlet.call.spawn(it) }
        val api = host.lookup<ListApi<String>>(list.ref)!!
        repeat(4) { api.inlet.call.add("e-$it") }
        started()
        awaitUntil("the list filled") { state(list.ref).page?.entries == 4 }

        val page = state(list.ref, limit = 2).page!!

        page.caveats.contains(StatePageView.POSITIONAL_CURSOR) shouldBe true
    }

    // -------------------------------------------------- the other three arms

    @Test
    fun `an open observation still answers from its fold, ignoring cursor and limit`() {
        val cell = set("a", "b", "c")
        val serving = started()
        probe.postForm("", observePath(cell.ref)).statusCode() shouldBe 204
        awaitUntil("the observation is open") { serving.observedRefs.isNotEmpty() }

        // even a cursor no table holds is ignored rather than answered 410:
        // paging is not a question an observed cell is asked (V1C-KERNEL
        // Decision 9 — View paging is out of scope)
        val response = probe.get(statePath(cell.ref) + "?cursor=p-nope&limit=1")

        response.statusCode() shouldBe 200
        val state = json.decodeFromString<CellState>(response.body())
        state.kind shouldBe CellState.VIEW
        state.page shouldBe null
        // a fold in the inspector's own heap is neither a live cell read nor a
        // checkpoint, so it claims neither
        state.provenance shouldBe null
        state.value.toString() shouldBe """["a","b","c"]"""
    }

    @Test
    fun `a Stateful cell with no bounded read still answers one whole copy`() {
        val counter = CounterCell().also { host.managementInlet.call.spawn(it) }
        host.lookup<CounterApi>(counter.ref)!!.inlet.call.increment(5)
        started()

        val state = state(counter.ref, limit = 1)

        state.kind shouldBe CellState.SNAPSHOT
        // the absent page object is how a client knows there was no bounded read
        state.page shouldBe null
        state.provenance shouldBe CellState.LIVE
        // byte-identical to what the shipped server answers today
        state.value.toString() shouldBe "5"
    }

    @Test
    fun `a read that misses the bounded wait answers unanswered, and costs one deadline`() {
        val cell = set("a")
        val serving = started()
        val issued = AtomicLong()
        val abandoned = CompletableFuture<civictech.cell.StateReadResult>()
        serving.reads = BoundedReadSource { _, _ -> issued.incrementAndGet(); abandoned }
        val snapshotsConsulted = AtomicLong()
        serving.snapshots = SnapshotSource { snapshotsConsulted.incrementAndGet(); null }

        val state = state(cell.ref)

        state.kind shouldBe CellState.UNAVAILABLE
        state.unreadable shouldBe CellState.UNANSWERED
        abandoned.isCancelled shouldBe true
        // one bounded wait, not two: an `Unavailable` (or a miss) is an answer,
        // never a reason to try the older whole-copy seam as well
        issued.get() shouldBe 1L
        snapshotsConsulted.get() shouldBe 0L
    }

    // ----------------------------------------------- provenance and coldness

    @Test
    fun `a suspended cell is read from its own quiescent fold, and stays suspended`() {
        val cell = set("a", "b", "c")
        started()
        host.managementInlet.call.suspend(cell.ref)
        awaitUntil("the cell is suspended") { host.isSuspended(cell.ref) }

        val state = state(cell.ref, limit = 2)

        state.kind shouldBe CellState.PAGE
        state.provenance shouldBe CellState.LIVE_SUSPENDED
        elementsOf(state) shouldContainExactly listOf("a", "b")
        // the read resumed nothing
        host.isSuspended(cell.ref) shouldBe true
    }

    @Test
    fun `a cell on a drained host answers from the checkpoint, and the host stays drained`() {
        val cell = set("a", "b", "c")
        started()
        host.managementInlet.call.drainHost()
        awaitUntil("the host drained") { host.isDrained }

        val state = state(cell.ref, limit = 2)

        // Decision 7's drained arm shipped as Unbounded(blob, CHECKPOINT): a
        // checkpoint blob is a whole `Stateful.snapshot()` value, not a page
        state.kind shouldBe CellState.SNAPSHOT
        state.page shouldBe null
        state.provenance shouldBe CellState.CHECKPOINT
        // real state, as of the drain
        state.value.toString() shouldBe """["a","b","c"]"""
        host.isDrained shouldBe true
    }

    @Test
    fun `a held ref answers migrating, never a stale local read`() {
        val cell = set("a", "b")
        started()
        registry.hold(cell.ref)

        val state = state(cell.ref)

        state.kind shouldBe CellState.UNAVAILABLE
        state.unreadable shouldBe CellState.MIGRATING
        state.value.toString() shouldBe "null"

        registry.release(cell.ref)
    }

    @Test
    fun `heat is readable for hot, suspended and drained, and cold for exactly the two that can be woken`() {
        Heat.HOT.isReadable shouldBe true
        Heat.SUSPENDED.isReadable shouldBe true
        Heat.DRAINED.isReadable shouldBe true
        Heat.HELD.isReadable shouldBe false
        Heat.UNHOSTED.isReadable shouldBe false

        // unchanged: cold is what the inspector may offer to end, which is a
        // different question from what it may read
        Heat.HOT.isCold shouldBe false
        Heat.SUSPENDED.isCold shouldBe true
        Heat.DRAINED.isCold shouldBe true
        Heat.HELD.isCold shouldBe false
        Heat.UNHOSTED.isCold shouldBe false
    }

    // ------------------------------------------------------------ ownership

    @Test
    fun `an exclusive payload is described, counted, and never encoded`() {
        val map = MapCell<String, Any>().also { host.managementInlet.call.spawn(it) }
        val api = host.lookup<MapApi<String, Any>>(map.ref)!!
        api.inlet.call.put("plain", "ordinary")
        api.inlet.call.put("held", Owned("s3cr3t-payload"))
        started()
        awaitUntil("both entries landed") { state(map.ref).page?.entries == 2 }

        val body = probe.state(statePath(map.ref))
        val state = json.decodeFromString<CellState>(body)

        state.page!!.exclusivesElided shouldBe 1
        // asserted on the serialized body, not on an intermediate object: no
        // payload value, no toString() of one, no encoded form of one
        body shouldNotContain "s3cr3t-payload"
        body shouldContain Owned::class.java.name
        body shouldContain "HELD"
        body shouldContain "ordinary"
    }

    // ------------------------------------------------------- the render seam

    /**
     * The reconciliation this endpoint's whole `$truncated` story rests on: a
     * page is served only when every entry the kernel returned was rendered, so
     * a byte-cut page is **re-read at the smaller limit** rather than served
     * with entries the cursor already advanced past silently missing.
     */
    @Test
    fun `a page the byte budget would cut is re-read smaller and served whole`() {
        val wide = "w".repeat(2_000)
        val cell = set(*(0 until 100).map { "$it-$wide" }.toTypedArray())
        started()

        val state = state(cell.ref, limit = 100)
        val page = state.page!!

        // the read was narrowed, so the page is genuinely smaller than asked for
        page.entries shouldBeLessThan 100
        page.entries shouldBeGreaterThan 0
        // …and every entry it names is in `value`: no top-level truncation
        rowsOf(state).size shouldBe page.entries
        state.value.toString() shouldNotContain ValueEncoder.TRUNCATED
        // more state exists, and the cursor is the only thing that says so
        page.cursor shouldNotBe null
    }

    /**
     * The marker's existing meaning, unchanged: `$truncated` *inside* a rendered
     * entry means one value was abbreviated, never that entries went missing.
     */
    @Test
    fun `one entry wider than the byte budget is still rendered, abbreviated`() {
        val huge = ArrayList((0 until 5_000).map { "element-$it-with-some-width" })
        val cell = SetCell<Any>().also { host.managementInlet.call.spawn(it) }
        host.lookup<SetApi<Any>>(cell.ref)!!.inlet.call.add(huge)
        started()
        awaitUntil("the wide entry landed") { state(cell.ref).page?.entries == 1 }

        val state = state(cell.ref)

        state.page!!.entries shouldBe 1
        rowsOf(state).size shouldBe 1
        // the abbreviation is inside the row, and the walk is complete
        state.value.toString() shouldContain ValueEncoder.TRUNCATED
        state.page!!.cursor shouldBe null
    }

    // -------------------------------------------------------------- leaking

    /**
     * P6, across a *full multi-page walk* rather than a single read — the shape
     * `InspectorDataSearchTest`'s leak check established, extended with the flow
     * feed's taps.
     */
    @Test
    fun `a full walk subscribes to nothing, taps nothing, and adds no cell or link`() {
        val cell = set(*(0 until 50).map { "e-$it" }.toTypedArray())
        val serving = started()
        val refsBefore = registry.localRefs()
        val linksBefore = registry.all().size
        val tapsBefore = serving.tappedOutlets

        val walk = walk(cell.ref, limit = 7)

        walk.flatMap { elementsOf(it) }.size shouldBe 50
        serving.observedRefs.shouldBeEmpty()
        serving.tappedOutlets shouldBe tapsBefore
        registry.localRefs() shouldContainExactly refsBefore
        registry.all().size shouldBe linksBefore
    }

    // ------------------------------------------------------------- the big one

    /**
     * The claim the whole V1c vertical rests on, driven end to end over HTTP: a
     * cell of ~10⁵ entries is walked page by page **while the graph keeps
     * serving other traffic**, and the walk is complete and duplicate-free.
     *
     * The traffic assertion is a *progress* assertion, never a timing one: a
     * second cell on the same host is fed continuously, and its served state —
     * read through this very endpoint — is strictly larger at the end of the
     * walk than it was after page 1. Before V1C-KERNEL the equivalent read was
     * one whole-state copy submitted at priority 0, which jumped the queue and
     * then owned the host thread for its duration
     * (`30-bounded-read-measurement.md` §4).
     */
    @Test
    fun `a hundred thousand entry cell walks end to end while the graph keeps serving`() {
        val big = bigSet(BIG_ENTRIES)
        val busy = set()
        val serving = started()

        val stop = AtomicBoolean(false)
        val offered = AtomicLong()
        val driver = Thread {
            val api = host.lookup<SetApi<String>>(busy.ref)!!
            while (!stop.get()) {
                api.inlet.call.add("busy-${offered.getAndIncrement()}")
                Thread.sleep(1)
            }
        }.apply { isDaemon = true; start() }

        try {
            var page = state(big.ref, limit = BIG_PAGE_LIMIT)
            page.kind shouldBe CellState.PAGE
            page.provenance shouldBe CellState.LIVE
            val servedAfterFirstPage = servedEntries(busy.ref)

            val seen = HashSet<String>(BIG_ENTRIES)
            var pages = 0
            while (true) {
                pages += 1
                val elements = elementsOf(page)
                elements.forEach { element -> seen.add(element) shouldBe true }
                val cursor = page.page!!.cursor ?: break
                page = json.decodeFromString(
                    probe.state(statePath(big.ref) + "?cursor=$cursor&limit=$BIG_PAGE_LIMIT"),
                )
                page.kind shouldBe CellState.PAGE
            }

            // the whole cell, once each, in a bounded number of bounded pages
            seen.size shouldBe BIG_ENTRIES
            pages shouldBeGreaterThan 1
            // the walk closed, and its stability verdict is a real answer
            page.page!!.cursor shouldBe null
            page.page!!.walkStable shouldBe true

            // …and the graph kept serving throughout: strictly more distinct
            // entries were served out of the other cell than page 1 of this
            // walk saw. Measured over a full walk, not one page — see
            // [servedEntries] for why one page cannot express this.
            servedEntries(busy.ref) shouldBeGreaterThan servedAfterFirstPage
            // no subscription, no tap, no new cell, for 10⁵ entries of reading
            serving.observedRefs.shouldBeEmpty()
            registry.localRefs() shouldContainExactly setOf(big.ref, busy.ref)
        } finally {
            stop.set(true)
            driver.join(5_000)
        }
    }

    // -------------------------------------------------------------- fixtures

    private fun set(vararg elements: String): SetCell<String> =
        SetCell<String>().also { cell ->
            host.managementInlet.call.spawn(cell)
            val api = host.lookup<SetApi<String>>(cell.ref)!!
            elements.forEach { api.inlet.call.add(it) }
            // one await for the whole seed, not one per element: a per-element
            // barrier is a whole-state copy each time, i.e. O(n²) setup
            if (elements.isNotEmpty()) {
                awaitUntil("${cell.ref} holds ${elements.size} elements") { holds(cell.ref, elements.size) }
            }
        }

    private fun add(cell: SetCell<String>, element: String) {
        val before = holdCount(cell.ref)
        host.lookup<SetApi<String>>(cell.ref)!!.inlet.call.add(element)
        awaitUntil("$element reached ${cell.ref}") { holdCount(cell.ref) > before }
    }

    private fun holds(ref: CellRef, elements: Int): Boolean = holdCount(ref) == elements

    private fun holdCount(ref: CellRef): Int =
        ((host.snapshotOf(ref).get() as? Map<*, *>)?.get("adds") as? Map<*, *>)?.size ?: 0

    /**
     * A cell of [count] entries built through `Stateful.restore` rather than
     * through [count] hosted `add` calls: this test is about reading 10⁵
     * entries, and seeding them one scheduler task at a time would spend the
     * whole test budget on the setup. `restore` is the same public seam a
     * checkpoint recovery uses, and the tags it installs are the shape
     * `SetCell.snapshot()` writes.
     */
    private fun bigSet(count: Int): SetCell<String> {
        val source = UUID.randomUUID()
        val adds = HashMap<String, MutableSet<Timestamp>>(count * 2)
        for (index in 0 until count) adds["e-$index"] = mutableSetOf(Timestamp(source, index + 1L))
        val state = HashMap<String, Any>()
        state["adds"] = adds
        state["dels"] = HashMap<String, MutableSet<Timestamp>>()
        state["counter"] = count.toLong()
        return SetCell<String>().also { cell ->
            cell.restore(state as Serializable)
            host.managementInlet.call.spawn(cell)
        }
    }

    private fun started(): InspectorServer {
        val started = InspectorServer(registry, mapOf("test-host" to host), port = 0).start()
        server = started
        probe = HttpProbe("http://localhost:${started.boundPort}")
        return started
    }

    private fun statePath(ref: CellRef) = "${InspectorServer.CELL_PATH}/${InspectorServer.encodeRef(ref)}/state"
    private fun observePath(ref: CellRef) = "${InspectorServer.CELL_PATH}/${InspectorServer.encodeRef(ref)}/observe"

    private fun state(ref: CellRef, limit: Int? = null): CellState =
        json.decodeFromString(probe.state(statePath(ref) + if (limit == null) "" else "?limit=$limit"))

    /** Page 1 and everything after it, following `page.cursor` to the end. */
    private fun walk(ref: CellRef, limit: Int): List<CellState> {
        val first = state(ref, limit = limit)
        return listOf(first) + resume(ref, first, limit)
    }

    /**
     * The pages after [from]. `limit` is re-sent on every request on purpose:
     * a cursor names a *position*, not a page size, so a client that stops
     * sending `?limit=` gets the default from there on — which is correct, and
     * worth a walk helper making explicit rather than accidental.
     */
    private fun resume(ref: CellRef, from: CellState, limit: Int): List<CellState> {
        val pages = ArrayList<CellState>()
        var cursor = from.page?.cursor
        while (cursor != null) {
            val next: CellState =
                json.decodeFromString(probe.state(statePath(ref) + "?cursor=$cursor&limit=$limit"))
            pages += next
            cursor = next.page?.cursor
        }
        return pages
    }

    /** The `$table` rows of a page's rendered value; empty for any other shape. */
    private fun rowsOf(state: CellState): List<JsonArray> {
        val table = (state.value as? JsonObject)?.get(ValueEncoder.TABLE) as? JsonObject ?: return emptyList()
        return (table["rows"] as? JsonArray)?.map { it.jsonArray } ?: emptyList()
    }

    /**
     * A `SetCell` page's elements. Its entries are `SetStateEntry(element,
     * addTags, delTags)` records, so the encoder renders them as a `$table`
     * whose first column is the element — see this ticket's report on why the
     * inspector forwards the kernel's page entries rather than re-interpreting
     * them into OR-set membership.
     */
    private fun elementsOf(state: CellState): List<String> =
        rowsOf(state).map { it[0].jsonPrimitive.content }

    /**
     * How many **distinct** entries [ref]'s served state currently holds, read
     * through this endpoint — counted over a *complete* walk, not off one page.
     *
     * The one-page reading this replaced was a saturating counter, which is why
     * the traffic assertion above went flaky. A page read with no `?limit=`
     * carries the server's default row bound, so once the cell holds that many
     * entries the reading reports that bound forever, however much the cell
     * grows afterwards. Two such readings then compare a constant with itself:
     * `200 should be > 200` (computenet-68e). A complete walk has no ceiling —
     * every request here sends an explicit `limit`, and the cursor is followed
     * to the end.
     *
     * Counting *distinct* elements rather than summing each page's `entries` is
     * deliberate. A walk over a cell that is still being written smears (its
     * `walkStable` latches false), and this cell is written continuously by
     * design, so the count must not depend on the walk being repetition-free.
     * A set makes any repetition harmless.
     *
     * That leaves exactly the property the assertion needs and no more: for an
     * add-only cell this count can only grow, so a strictly larger reading is
     * evidence the host served new entries in between. It is a lower bound on
     * the cell's size rather than a measurement of it — which is all a
     * *progress* assertion should rest on.
     */
    private fun servedEntries(ref: CellRef): Int =
        walk(ref, limit = BIG_PAGE_LIMIT).flatMapTo(HashSet()) { elementsOf(it) }.size

    private infix fun Any?.shouldNotBe(other: Any?) {
        (this == other) shouldBe false
    }

    private companion object {
        /** ~10⁵ — the size `30-bounded-read-measurement.md` measured the stall at. */
        const val BIG_ENTRIES = 100_000

        /**
         * Below the ceiling, and small enough that one page's rendered rows fit
         * the encoder's 50 KB byte budget without a re-read — so the walk
         * measures paging, not the reconciliation (which has its own test).
         */
        const val BIG_PAGE_LIMIT = 400
    }
}
