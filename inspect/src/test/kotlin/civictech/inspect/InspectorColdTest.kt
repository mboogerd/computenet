package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.LinkResult
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue

/**
 * M5-COLD — cold graphs: list without waking, wake explicitly
 * (`doc/spec/90-roadmap/97-inspector-plan/tickets/M5-COLD.md`).
 *
 * What these tests pin, in the ticket's own order:
 *
 * 1. the **predicate** — a component is cold when every one of its cells is
 *    parked, by either of the two kernel senses the inspector can read
 *    ([Heat]): individually suspended, or on a drained host. One running cell
 *    keeps the whole graph hot;
 * 2. **listing costs nothing** — coldness is computed from registry and host
 *    metadata, so listing a cold graph creates no observation, spawns no cell,
 *    raises no attention, and its structure stays servable;
 * 3. the **wake** — `POST /graph/{id}/wake` resumes through the kernel's own
 *    seams, and the transition is announced as `lifecycle` events;
 * 4. **search integration** — a cold component is skipped whole and its cells
 *    counted in `SearchCost.coldSkipped`.
 *
 * Cell uuids are fixed so "lexicographically-min member" — the component id
 * heuristic — is a fact the assertions can state rather than recompute.
 */
class InspectorColdTest {

    private val json = Json { ignoreUnknownKeys = false }
    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private val other = ManagedHost(registry = registry)
    private var server: InspectorServer? = null
    private lateinit var probe: HttpProbe
    private var tap: SseTap? = null

    @AfterEach
    fun tearDown() {
        tap?.close()
        server?.close()
    }

    // ------------------------------------------------------------- predicate

    @Test
    fun `a component whose cells are all suspended lists as cold`() {
        val (a, b) = pair(host, A, B)
        started()

        suspendCells(host, a, b)

        graphs().graphs.single().lifecycle shouldBe "cold"
    }

    /**
     * All, not any. A component with one cell still running is computing, and
     * a card that called it cold would put a "wake" button in front of a graph
     * that is already awake.
     */
    @Test
    fun `one running cell keeps its whole component hot`() {
        val (a, _) = pair(host, A, B)
        started()

        suspendCells(host, a)

        graphs().graphs.single().lifecycle shouldBe "hot"
    }

    /** The second sense of cold the kernel can report: the host itself drained. */
    @Test
    fun `a component on a drained host lists as cold`() {
        pair(other, A, B)
        started()

        drain(other)

        graphs().graphs.single().lifecycle shouldBe "cold"
    }

    /**
     * A held ref is mid-flip, not parked: it is still running, and the only
     * thing that may end the window is the migration that opened it — so the
     * inspector must not advertise a wake for it (see [Heat]'s doc).
     */
    @Test
    fun `a held cell is not cold, because waking is not the inspector's to offer`() {
        val (a, b) = pair(host, A, B)
        started()

        registry.hold(a)
        registry.hold(b)

        graphs().graphs.single().lifecycle shouldBe "hot"

        registry.release(a)
        registry.release(b)
    }

    @Test
    fun `each component's coldness is its own`() {
        val (a, b) = pair(host, A, B)
        pair(host, C, D)
        started()

        suspendCells(host, a, b)

        graphs().graphs.associate { it.id to it.lifecycle } shouldBe mapOf("g-$A" to "cold", "g-$C" to "hot")
    }

    /** The card and the canvas answer from one stamp, so they cannot disagree. */
    @Test
    fun `a cold component's nodes report the contract's SUSPENDED lifecycle`() {
        val (a, b) = pair(host, A, B)
        started()

        suspendCells(host, a, b)

        snapshot(null).nodes.map { it.lifecycle } shouldContainExactly listOf("SUSPENDED", "SUSPENDED")
    }

    // -------------------------------------------------- listing costs nothing

    /**
     * The M1-EVAL leak check, applied to the thing this milestone adds: listing
     * a cold graph — its card, its scoped topology, its detail body — creates
     * no `ObserveCell` sink, publishes no new ref, and adds no link. Coldness
     * is registry and host metadata, and reading metadata is free.
     */
    @Test
    fun `computing coldness subscribes to nothing and touches no cell`() {
        val (a, b) = pair(host, A, B)
        val serving = started()
        suspendCells(host, a, b)
        val refsBefore = registry.localRefs()
        val linksBefore = registry.all().size

        graphs().graphs.single().lifecycle shouldBe "cold"
        snapshot("g-$A").nodes.size shouldBe 2
        probe.get("${InspectorServer.CELL_PATH}/${encoded(a)}").statusCode() shouldBe 200

        serving.observedRefs.shouldBeEmptySet()
        registry.localRefs() shouldContainExactly refsBefore
        registry.all().size shouldBe linksBefore
        // and it is genuinely still suspended afterwards — nothing woke it up
        host.isSuspended(a) shouldBe true
    }

    /** Ticket Implement §1: "Structure (topology) of a cold graph remains servable". */
    @Test
    fun `a cold graph still serves its structure`() {
        val (a, b) = pair(host, A, B)
        started()
        suspendCells(host, a, b)

        val scoped = snapshot("g-$A")

        scoped.nodes.map { it.ref } shouldContainExactly listOf(encoded(a), encoded(b))
        scoped.edges.size shouldBe 1
    }

    // ------------------------------------------------------------------ wake

    @Test
    fun `waking a suspended component resumes its cells and reports what it took`() {
        val (a, b) = pair(host, A, B)
        started()
        suspendCells(host, a, b)

        val response = wake("g-$A")

        response.statusCode() shouldBe 202
        val body = json.parseToJsonElement(response.body()).jsonObject
        body["graph"]!!.jsonPrimitive.content shouldBe "g-$A"
        body["cells"]!!.jsonPrimitive.content shouldBe "2"
        body["hosts"]!!.jsonPrimitive.content shouldBe "0"

        awaitUntil("both cells resumed") { !host.isSuspended(a) && !host.isSuspended(b) }
        awaitUntil("the card goes hot") { graphs().graphs.single().lifecycle == "hot" }
    }

    @Test
    fun `waking a drained host's component resumes the host`() {
        pair(other, A, B)
        started()
        drain(other)

        val response = wake("g-$A")

        response.statusCode() shouldBe 202
        json.parseToJsonElement(response.body()).jsonObject["hosts"]!!.jsonPrimitive.content shouldBe "1"
        awaitUntil("the host resumed") { !other.isDrained }
        awaitUntil("the card goes hot") { graphs().graphs.single().lifecycle == "hot" }
    }

    /**
     * The transition is *logged*: the client learns it from the event stream.
     *
     * V2 changed how, not what: the `lifecycle` events are pushed by the
     * kernel's own suspend/resume notification
     * (`ManagedHost.onLifecycle` → [InspectorModel.lifecycleChanged]), so the
     * `tickAll()` that used to be needed to *produce* them is gone — the
     * suspends before [listen] have already been announced by the time a client
     * attaches. The remaining `tickAll()` drives only the coalesced
     * `graphs.changed` card invalidation, which stays on the 1 Hz
     * `"graphsChanged"` tick by design (see [InspectorModel.publishGraphChanges]).
     */
    @Test
    fun `a wake is announced as lifecycle events`() {
        val (a, b) = pair(host, A, B)
        val serving = started()
        suspendCells(host, a, b)
        val events = listen()

        wake("g-$A").statusCode() shouldBe 202
        awaitUntil("both cells resumed") { !host.isSuspended(a) && !host.isSuspended(b) }

        events.awaitKind(Event.LIFECYCLE, 2)
        events.lifecyclesOf(encoded(a)) shouldContainExactly listOf("HOT")
        events.lifecyclesOf(encoded(b)) shouldContainExactly listOf("HOT")
        // and the card the navigator is showing is invalidated with them
        serving.tickAll()
        events.awaitKind(Event.GRAPHS_CHANGED, 1)
    }

    /** V2: pushed by the suspend itself — no tick, and still nothing asked. */
    @Test
    fun `going cold is announced too, without anything asking the cells`() {
        val (a, b) = pair(host, A, B)
        val serving = started()
        val events = listen()

        suspendCells(host, a, b)

        events.awaitKind(Event.LIFECYCLE, 2)
        events.lifecyclesOf(encoded(a)) shouldContainExactly listOf("SUSPENDED")
        serving.observedRefs.shouldBeEmptySet()
    }

    /** Idempotent: waking a hot graph is a no-op success, not an error. */
    @Test
    fun `waking a hot graph resumes nothing and still answers 202`() {
        pair(host, A, B)
        started()

        val response = wake("g-$A")

        response.statusCode() shouldBe 202
        json.parseToJsonElement(response.body()).jsonObject["cells"]!!.jsonPrimitive.content shouldBe "0"
    }

    /**
     * Unlike `GET /topology?graph=`'s empty 200 — a stale id on a *read* is an
     * ordinary race, but "wake this graph" naming nothing did not happen.
     */
    @Test
    fun `waking an unknown graph is a 404`() {
        pair(host, A, B)
        started()

        wake("g-nope").statusCode() shouldBe 404
    }

    @Test
    fun `the wake route rejects anything but POST wake, and does not shadow GET graphs`() {
        pair(host, A, B)
        started()

        probe.get("${InspectorServer.GRAPH_PATH}/g-$A/wake").statusCode() shouldBe 404
        probe.postForm("", "${InspectorServer.GRAPH_PATH}/g-$A").statusCode() shouldBe 404
        // the shorter context must not have swallowed the graph list
        probe.get(InspectorServer.GRAPHS_PATH).statusCode() shouldBe 200
    }

    /**
     * T24 — the routing-distinctness regression the prefix-length trap
     * protects: `GRAPHS_PATH` ("…/graphs") and `GRAPH_PATH` ("…/graph", one
     * character shorter, registered after) must each reach their *own*
     * handler in the same running server, not just fail closed individually.
     * `GET /api/inspect/graphs` answers the live graph list, and
     * `POST /api/inspect/graph/{id}/wake` is independently accepted and
     * resolves the same graph — neither route silently served by the other's
     * handler.
     */
    @Test
    fun `GET graphs and POST graph wake reach distinct handlers`() {
        pair(host, A, B)
        started()

        graphs().graphs.map { it.id } shouldContain "g-$A"

        val woken = wake("g-$A")
        woken.statusCode() shouldBe 202
        json.parseToJsonElement(woken.body()).jsonObject["graph"]!!.jsonPrimitive.content shouldBe "g-$A"
    }

    /**
     * T19 — the CORS-simple-request gate. A plain cross-origin `POST` with no
     * custom header (the shape a browser sends with no preflight, and the
     * shape `allowCrossOrigin()`'s old KDoc wrongly treated as harmless for
     * this route) is rejected before {@link Waker.wake} ever runs: neither
     * cell resumes, and the caller gets a 4xx, not a 202.
     */
    @Test
    fun `a wake POST without the required header is rejected, and nothing wakes`() {
        val (a, b) = pair(host, A, B)
        started()
        suspendCells(host, a, b)

        val response = wakeWithoutHeader("g-$A")

        response.statusCode() shouldBe 400
        host.isSuspended(a) shouldBe true
        host.isSuspended(b) shouldBe true
    }

    /** The header alone does not conjure a graph that was never suspended — it
     *  only clears the gate; the rest of the route's behavior is unchanged. */
    @Test
    fun `a wake POST with the required header still succeeds`() {
        val (a, b) = pair(host, A, B)
        started()
        suspendCells(host, a, b)

        wake("g-$A").statusCode() shouldBe 202

        awaitUntil("both cells resumed") { !host.isSuspended(a) && !host.isSuspended(b) }
    }

    // ----------------------------------------------------- search integration

    /** Ticket Implement §3: a cold component is skipped whole, and counted. */
    @Test
    fun `a data search skips a cold component and counts its cells`() {
        val (a, b) = pair(host, A, B)
        pair(host, C, D)
        started()
        suspendCells(host, a, b)

        val result = search("anything")

        result.cost!!.coldSkipped shouldBe 2
        // the hot component's two cells were still read
        result.cost!!.cellsQueried shouldBe 2
        result.hits.single().graph shouldBe DataSearch.NOTICE_GRAPH
        result.hits.single().detail shouldContain "1 cold graph skipped — wake to include"
    }

    @Test
    fun `a data search counts a drained host's cells as cold too`() {
        pair(other, A, B)
        started()
        drain(other)

        val result = search("anything")

        result.cost shouldBe SearchCost(cellsQueried = 0, coldSkipped = 2)
    }

    // -------------------------------------------------------------- fixtures

    private fun spawn(on: ManagedHost, uuid: String): CellRef {
        val ref = CellRef(UUID.fromString(uuid))
        on.managementInlet.call.spawn(SetCell<Any>(ref = ref))
        return ref
    }

    /** Two fresh cells on one host, linked into one component. */
    private fun pair(on: ManagedHost, first: String, second: String): Pair<CellRef, CellRef> {
        val a = spawn(on, first)
        val b = spawn(on, second)
        on.managementInlet.call.connect(a, "outlet", b, "deltaInlet") as LinkResult.Connected
        return a to b
    }

    private fun suspendCells(on: ManagedHost, vararg refs: CellRef) {
        refs.forEach { on.managementInlet.call.suspend(it) }
        awaitUntil("cells suspended") { refs.all { on.isSuspended(it) } }
    }

    private fun drain(on: ManagedHost) {
        on.managementInlet.call.drainHost()
        // DRAINING is not DRAINED: the drain body runs below data priority, and
        // the whole point of `isDrained` is that an observer can tell
        awaitUntil("host drained") { on.isDrained }
    }

    private fun started(): InspectorServer {
        val started = InspectorServer(registry, mapOf("h" to host, "other" to other), port = 0).start()
        server = started
        probe = HttpProbe("http://localhost:${started.boundPort}")
        return started
    }

    private fun graphs(): GraphList = json.decodeFromString(probe.state(InspectorServer.GRAPHS_PATH))

    private fun snapshot(graph: String?): TopologySnapshot = json.decodeFromString(
        probe.state(InspectorServer.TOPOLOGY_PATH + if (graph == null) "" else "?graph=$graph"),
    )

    private fun search(query: String): SearchResult =
        json.decodeFromString(probe.state("${InspectorServer.SEARCH_PATH}?mode=data&q=$query"))

    /**
     * `HttpProbe` has no header-carrying POST, and the T19 gate lives on a
     * header — so wake requests go straight through a plain [HttpClient]
     * here, with [withHeader] choosing whether [InspectorServer.WAKE_HEADER]
     * is attached.
     */
    private fun wake(graph: String): HttpResponse<String> = sendWake(graph, withHeader = true)

    private fun wakeWithoutHeader(graph: String): HttpResponse<String> = sendWake(graph, withHeader = false)

    private fun sendWake(graph: String, withHeader: Boolean): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:${server!!.boundPort}${InspectorServer.GRAPH_PATH}/$graph/wake"))
            .POST(HttpRequest.BodyPublishers.ofString(""))
        if (withHeader) builder.header(InspectorServer.WAKE_HEADER, InspectorServer.WAKE_HEADER_VALUE)
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun encoded(ref: CellRef): String = InspectorServer.encodeRef(ref)

    private fun Set<CellRef>.shouldBeEmptySet() = this shouldBe emptySet<CellRef>()

    private fun listen(): SseTap {
        val opened = SseTap("http://localhost:${server!!.boundPort}${InspectorServer.EVENTS_PATH}")
        tap = opened
        awaitUntil("sse client attached", timeoutMs = 5_000) { server!!.attachedClients > 0 }
        return opened
    }

    /** A live `text/event-stream` reader, retaining each frame's kind and payload. */
    private inner class SseTap(url: String) : AutoCloseable {
        private val frames = LinkedBlockingQueue<Event>()
        private val reader: CompletableFuture<Void> = HttpClient.newHttpClient()
            .sendAsync(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofLines())
            .thenAccept { response ->
                response.body().forEach { line ->
                    if (line.startsWith(DATA)) frames += json.decodeFromString<Event>(line.removePrefix(DATA))
                }
            }

        fun countOfKind(kind: String): Int = frames.count { it.kind == kind }

        /** Every `lifecycle` value announced for [ref], in order. */
        fun lifecyclesOf(ref: String): List<String> = frames
            .filter { it.kind == Event.LIFECYCLE && it.payload["ref"]?.jsonPrimitive?.content == ref }
            .map { it.payload["lifecycle"]!!.jsonPrimitive.content }

        fun awaitKind(kind: String, count: Int) =
            awaitUntil("$count '$kind' frames (saw ${countOfKind(kind)})", timeoutMs = 10_000) {
                countOfKind(kind) >= count
            }

        override fun close() {
            reader.cancel(true)
        }
    }

    private companion object {
        const val DATA = "data: "

        // fixed and ordered: A < B < C < D lexicographically
        const val A = "0a000000-0000-4000-8000-000000000000"
        const val B = "0b000000-0000-4000-8000-000000000000"
        const val C = "0c000000-0000-4000-8000-000000000000"
        const val D = "0d000000-0000-4000-8000-000000000000"
    }
}
