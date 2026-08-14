package civictech.inspect

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.data.CounterApi
import civictech.cell.data.CounterCell
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.host.lookup
import civictech.cell.observe.View
import civictech.cell.observe.observe
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue

/**
 * The M1 state vertical end to end: the explicit subscription
 * (`POST`/`DELETE /cell/{ref}/observe`), the reads it enables
 * (`GET /cell/{ref}/state`), and the `state.summary` events it streams.
 *
 * The invariant under test throughout is P6 — observation is causal, so it is
 * opt-in per cell and must be genuinely released, not merely forgotten.
 */
class InspectorObserveTest {

    private val json = Json { ignoreUnknownKeys = false }
    private val registry = LocationRegistry()

    /**
     * The host's scheduler, owned here rather than left to [ManagedHost]'s own
     * default, purely so [tearDown] can stop it (computenet-4vh) — see
     * `InspectorErrorsTest` for the full rationale.
     */
    private val hostRef = CellRef(java.util.UUID.randomUUID())
    private val hostScheduler = VirtualThreadScheduler("ManagedHost-${hostRef.id}")
    private val host = ManagedHost(ref = hostRef, scheduler = hostScheduler, registry = registry)
    private val server = InspectorServer(registry, mapOf("test-host" to host), port = 0).start()
    private val probe = HttpProbe("http://localhost:${server.boundPort}")
    private var tap: SseTap? = null

    @AfterEach
    fun tearDown() {
        tap?.close()
        server.close()
        probe.close()
        hostScheduler.shutdown()
    }

    private fun observePath(ref: CellRef) = "${InspectorServer.CELL_PATH}/${InspectorServer.encodeRef(ref)}/observe"
    private fun statePath(ref: CellRef) = "${InspectorServer.CELL_PATH}/${InspectorServer.encodeRef(ref)}/state"

    private fun set(): SetCell<String> = SetCell<String>().also { host.managementInlet.call.spawn(it) }

    private fun add(cell: SetCell<String>, element: String) {
        host.lookup<SetApi<String>>(cell.ref)!!.inlet.call.add(element)
    }

    private fun state(ref: CellRef): CellState = json.decodeFromString(probe.state(statePath(ref)))

    // -------------------------------------------------------------- lifecycle

    @Test
    fun `browsing does not subscribe, observing does, and releasing frees the sink`() {
        val cell = set()
        add(cell, "ada")

        // 1. browsing: reading detail and state raises nothing at all — no
        // subscription is created (server.observedRefs stays empty). V0-BE:
        // SetCell is Stateful, so the wired fallback now answers its raw state
        // here instead of CellState.UNAVAILABLE; that fallback read is
        // host-routed and one-shot, not an observation. V1C-BE: SetCell is
        // `BoundedStateful` too, so the answer is one bounded *page* rather than
        // a whole copy — still one host-routed read, still no subscription.
        probe.get("${InspectorServer.CELL_PATH}/${InspectorServer.encodeRef(cell.ref)}").statusCode() shouldBe 200
        state(cell.ref).kind shouldBe CellState.PAGE
        registry.localRefs() shouldBe setOf(cell.ref)
        server.observedRefs shouldBe emptySet()

        // 2. observing: one sink spawned and linked to the cell's outlet
        probe.postForm("", observePath(cell.ref)).statusCode() shouldBe 204
        server.observedRefs shouldBe setOf(cell.ref)
        val sinkRef = awaitSink(cell.ref)
        registry.swapSet(sinkRef).size shouldBe 1

        // its fold already carries the state that predates the subscription
        val observed = state(cell.ref)
        observed.kind shouldBe CellState.VIEW
        observed.value.toString() shouldBe """["ada"]"""

        // 3. releasing: unlinked *and* despawned — a leaked sink keeps the
        // upstream cone's attention raised, so neither half is optional
        probe.delete(observePath(cell.ref)).statusCode() shouldBe 204
        server.observedRefs shouldBe emptySet()
        awaitUntil("observe sink $sinkRef despawned") { sinkRef !in registry.localRefs() }
        registry.swapSet(sinkRef) shouldBe emptySet()
        registry.locate(sinkRef) shouldBe null
        // released, not gone: the wired fallback still answers this cell's
        // state, exactly as it did before it was ever observed (V1C-BE: as a
        // bounded page, since SetCell is `BoundedStateful`)
        state(cell.ref).kind shouldBe CellState.PAGE
    }

    @Test
    fun `a released observation's sink is never folded again`() {
        val cell = set()
        probe.postForm("", observePath(cell.ref)).statusCode() shouldBe 204
        val sinkRef = awaitSink(cell.ref)
        add(cell, "ada")
        awaitUntil("the fold caught the add") { state(cell.ref).value.toString() == """["ada"]""" }

        probe.delete(observePath(cell.ref)).statusCode() shouldBe 204
        awaitUntil("observe sink despawned") { sinkRef !in registry.localRefs() }

        // the cell keeps working, and its outlet no longer reaches the sink:
        // the fold is not merely ignored, it is never invoked again — a
        // delivery to the despawned sink would dead-letter
        val deadLettersBefore = host.supervisionAccounting().deadLetters
        add(cell, "grace")
        // the add is observable through a *fresh* subscription, so waiting on
        // it is a real barrier rather than a sleep-and-hope
        probe.postForm("", observePath(cell.ref)).statusCode() shouldBe 204
        awaitUntil("the post-release add landed in the cell") {
            state(cell.ref).value.toString() == """["ada","grace"]"""
        }

        host.supervisionAccounting().deadLetters shouldBe deadLettersBefore
    }

    @Test
    fun `re-observing an already-observed cell is idempotent`() {
        val cell = set()
        probe.postForm("", observePath(cell.ref)).statusCode() shouldBe 204
        val sinkRef = awaitSink(cell.ref)

        probe.postForm("", observePath(cell.ref)).statusCode() shouldBe 204

        server.observedRefs shouldBe setOf(cell.ref)
        // still exactly one sink: a second POST renews the deadline, it does
        // not spawn a second fold onto the same outlet
        sinks(cell.ref) shouldBe setOf(sinkRef)
    }

    @Test
    fun `releasing an observation that was never opened is a no-op success`() {
        val cell = set()

        probe.delete(observePath(cell.ref)).statusCode() shouldBe 204
        registry.localRefs() shouldBe setOf(cell.ref)
    }

    @Test
    fun `closing the server releases every observation it opened`() {
        val cell = set()
        probe.postForm("", observePath(cell.ref)).statusCode() shouldBe 204
        val sinkRef = awaitSink(cell.ref)

        server.close()

        awaitUntil("observe sink despawned on close") { sinkRef !in registry.localRefs() }
        registry.swapSet(sinkRef) shouldBe emptySet()
    }

    // ------------------------------------------------------------ refusals

    @Test
    fun `a cell the kernel offers no fold for is refused rather than silently unobserved`() {
        // CounterCell emits CounterDelta; no built-in View folds it
        val counter = CounterCell().also { host.managementInlet.call.spawn(it) }

        val response = probe.postForm("", observePath(counter.ref))

        response.statusCode() shouldBe 409
        server.observedRefs shouldBe emptySet()
        // V0-BE: no *fold* refuses the subscription, but CounterCell is still
        // `Stateful` and locally hosted, so InspectorServer's wired snapshot
        // default now answers it — the seam this ticket exists to close (see
        // the snapshot-fallback tests below for the dedicated coverage).
        state(counter.ref).kind shouldBe CellState.SNAPSHOT
    }

    @Test
    fun `an observation sink is itself unobservable — it has no outlet`() {
        val cell = set()
        // the app's own sink, not one the inspector made
        host.observe(cell.ref, View.set<String>())
        val sinkRef = awaitSink(cell.ref)

        probe.postForm("", observePath(sinkRef)).statusCode() shouldBe 409
    }

    @Test
    fun `observing or reading an unknown cell is a 404`() {
        val unknown = CellRef(java.util.UUID.randomUUID())

        probe.postForm("", observePath(unknown)).statusCode() shouldBe 404
        probe.get(statePath(unknown)).statusCode() shouldBe 404
        // DELETE stays idempotent even for a ref that never existed
        probe.delete(observePath(unknown)).statusCode() shouldBe 204
    }

    // ---------------------------------------------------------- state + events

    @Test
    fun `state carries the fold, its wave stamp and its staleness`() {
        val cell = set()
        probe.postForm("", observePath(cell.ref)).statusCode() shouldBe 204
        awaitSink(cell.ref)

        // before any wave reaches the fold there is no frontier to report
        state(cell.ref).frontier shouldBe null

        add(cell, "ada")
        awaitUntil("fold caught the add") { state(cell.ref).value.toString() == """["ada"]""" }

        val settled = state(cell.ref)
        settled.kind shouldBe CellState.VIEW
        settled.ref shouldBe InspectorServer.encodeRef(cell.ref)
        // the stamp is the producing outlet's wave position for this delta
        settled.frontier!!.counter shouldBe 1L
        (settled.staleMs >= 0) shouldBe true
    }

    @Test
    fun `an open subscription streams windowed state summaries, and only for the subscribed cell`() {
        val observed = set()
        val ignored = set()
        val events = listen()

        probe.postForm("", observePath(observed.ref)).statusCode() shouldBe 204
        // V1A-BE: the window publishes, not the change. Before this ticket the
        // subscription's own late-join catch-up emitted a summary the instant
        // the listener was registered; it is now folded into the first window,
        // which the tick — here driven synchronously — is what releases.
        server.tickAll()
        val first = events.awaitKind(Event.STATE_SUMMARY, 1).first()
        first["ref"]!!.jsonPrimitive.content shouldBe InspectorServer.encodeRef(observed.ref)
        first["cardinality"]!!.jsonPrimitive.content shouldBe "0 rows"

        add(observed, "ada")
        add(ignored, "noise")
        awaitUntil("the fold caught the add") { state(observed.ref).value.toString() == """["ada"]""" }
        server.tickAll()

        awaitUntil("a window carrying the settled add") {
            events.payloadsOf(Event.STATE_SUMMARY).any { it["cardinality"]!!.jsonPrimitive.content == "1 row" }
        }
        val settled = events.payloadsOf(Event.STATE_SUMMARY)
            .first { it["cardinality"]!!.jsonPrimitive.content == "1 row" }
        settled["frontier"]!!.jsonObject["counter"]!!.jsonPrimitive.content shouldBe "1"
        // additive to M1's four fields: how many settled effective changes the
        // window coalesced (see InspectorModel.stateSummary)
        (settled["changes"]!!.jsonPrimitive.long >= 1) shouldBe true
        // the other cell has no observation, so it has no window at all (P6)
        events.payloadsOf(Event.STATE_SUMMARY).map { it["ref"]!!.jsonPrimitive.content }.toSet() shouldBe
            setOf(InspectorServer.encodeRef(observed.ref))
    }

    @Test
    fun `a quiet window is published too, with staleMs growing and nothing else moving`() {
        val cell = set()
        val events = listen()
        probe.postForm("", observePath(cell.ref)).statusCode() shouldBe 204
        add(cell, "ada")
        awaitUntil("the fold caught the add") { state(cell.ref).value.toString() == """["ada"]""" }

        // three windows, nothing happening in any of them: silence would be
        // indistinguishable from a released observation, a dropped frame or a
        // stopped server, so a quiet window says "quiet" instead
        repeat(4) { server.tickAll() }
        val quiet = events.awaitKind(Event.STATE_SUMMARY, 4).takeLast(3)

        quiet.map { it["changes"]!!.jsonPrimitive.long } shouldBe listOf(0L, 0L, 0L)
        quiet.map { it["cardinality"]!!.jsonPrimitive.content }.toSet() shouldBe setOf("1 row")
        quiet.map { it["frontier"]!!.jsonObject["counter"]!!.jsonPrimitive.content }.toSet() shouldBe setOf("1")
        val stale = quiet.map { it["staleMs"]!!.jsonPrimitive.long }
        stale.zipWithNext().forEach { (earlier, later) -> (later >= earlier) shouldBe true }
    }

    @Test
    fun `an effective change is announced within one window, and the state read on it is fresh`() {
        val cell = set()
        val events = listen()
        probe.postForm("", observePath(cell.ref)).statusCode() shouldBe 204

        add(cell, "ada")

        // deliberately no `tickAll()`: the *scheduled* window is what has to
        // announce this, which is the freshness guarantee the whole live-value
        // story rests on. The wait is bounded at several windows so it proves
        // the announcement happens without asserting on scheduler timing. The
        // fold is empty until the add, so a "1 row" window can only be one
        // that carries it.
        awaitUntil("the scheduled window announced the change", timeoutMs = 5 * Observations.WINDOW_MS) {
            events.payloadsOf(Event.STATE_SUMMARY).any {
                it["cardinality"]!!.jsonPrimitive.content == "1 row" && it["changes"]!!.jsonPrimitive.long > 0
            }
        }
        // what the client does on a summary it judges to indicate change
        state(cell.ref).value.toString() shouldBe """["ada"]"""
    }

    @Test
    fun `releasing publishes a trailing summary, and then the feed is silent for that cell`() {
        val cell = set()
        val events = listen()
        probe.postForm("", observePath(cell.ref)).statusCode() shouldBe 204
        server.tickAll()
        events.awaitKind(Event.STATE_SUMMARY, 1)

        probe.delete(observePath(cell.ref)).statusCode() shouldBe 204

        // a frame of another kind is the flush barrier: one SSE connection
        // delivers in order, so once it lands every summary queued before it
        // has landed too — no sleep, no count racing the socket
        set()
        events.awaitKind(Event.TOPOLOGY_NODE, 1)
        val atRelease = events.countOfKind(Event.STATE_SUMMARY)

        repeat(3) { server.tickAll() }
        set()
        events.awaitKind(Event.TOPOLOGY_NODE, 2)

        // exactly nothing after the trailing one: a released observation owns
        // no window, so neither the ticks above nor the scheduler produce one
        events.countOfKind(Event.STATE_SUMMARY) shouldBe atRelease
    }

    @Test
    fun `state summaries share the topology stream's sequence`() {
        val cell = set()
        val events = listen()
        probe.postForm("", observePath(cell.ref)).statusCode() shouldBe 204

        // the sink itself is an instrument, not a topology delta (M5-EVAL), so
        // a real topology change supplies the second event kind; every frame's
        // seq is strictly increasing so the client's one gap detector covers
        // both kinds
        events.awaitKind(Event.STATE_SUMMARY, 1)
        set()
        events.awaitKind(Event.TOPOLOGY_NODE, 1)
        val frames = events.awaitAtLeast(2)
        val first = frames.first().seq
        frames.map { it.seq } shouldBe frames.indices.map { first + it }
        frames.map { it.kind }.contains(Event.STATE_SUMMARY) shouldBe true
        frames.map { it.kind }.contains(Event.TOPOLOGY_NODE) shouldBe true
    }

    // ------------------------------------------------- the snapshot fallback

    @Test
    fun `a wired snapshot source answers for a cell with no observation`() {
        val cell = set()
        add(cell, "ada")
        // V1C-BE: SetCell is `BoundedStateful` since wave 9, so the bounded seam
        // would answer this cell first. Disabling it is what keeps this test the
        // coverage it has always been — the whole-copy labelling and encoding
        // path, `kind: "snapshot"` — rather than quietly becoming a second paged
        // test. `BoundedReadSource.Unavailable` is exactly the "no bounded read
        // wired" case the seam declares for it.
        server.reads = BoundedReadSource.Unavailable
        // stands in for the kernel accessor M1 does not have (see SnapshotSource):
        // what matters here is that the reader encodes and labels it correctly
        server.snapshots = SnapshotSource { ref -> if (ref == cell.ref) cell.snapshot() else null }

        // `add` is asynchronous, and this stand-in source reads the cell on the
        // HTTP thread without waiting for the data band to drain — the same
        // unbounded delivery assumption as the wired-default test below, so the
        // same bounded await rather than a single hopeful read.
        lateinit var answer: CellState
        awaitUntil("the whole-copy read carries the added element") {
            answer = state(cell.ref)
            answer.kind == CellState.SNAPSHOT && answer.value.toString() == """["ada"]"""
        }

        answer.kind shouldBe CellState.SNAPSHOT
        answer.value.toString() shouldBe """["ada"]"""
        answer.frontier shouldBe null
    }

    @Test
    fun `an open observation wins over the snapshot source`() {
        val cell = set()
        server.snapshots = SnapshotSource { ArrayList(listOf("from-the-snapshot")) }
        probe.postForm("", observePath(cell.ref)).statusCode() shouldBe 204
        awaitSink(cell.ref)

        state(cell.ref).kind shouldBe CellState.VIEW
    }

    @Test
    fun `the wired default answers a Stateful cell no built-in View can fold, unobserved`() {
        // CounterDelta has no built-in fold (see the refusal test above), but
        // CounterCell is Stateful and locally hosted — exactly the case
        // InspectorServer's *default* SnapshotSource (V0-BE, routed through
        // ManagedHost.snapshotOf) exists to answer. No SnapshotSource is
        // assigned here: this exercises the shipped wiring, not a stand-in.
        val counter = CounterCell().also { host.managementInlet.call.spawn(it) }
        host.lookup<CounterApi>(counter.ref)!!.inlet.call.increment(5)

        // `increment` is an ordinary data-band invocation (priority 20); the
        // snapshot read above it is submitted at priority 0, which — as
        // `ManagedHost.readState`'s KDoc puts it for the submit both reads share
        // — "jumps ahead of every queued data-priority task". A single read
        // taken straight after the call is therefore free to answer the
        // *pre*-increment total — not rarely, but whenever the host thread has
        // not drained the data band yet, which on a loaded runner is ordinary.
        // Two wrong answers are reachable, and the condition gates both: the
        // value, because a read that overtakes the queued increment answers 0;
        // and `kind`, because a host thread still busy at
        // `InspectorServer.SNAPSHOT_WAIT_MS` answers UNAVAILABLE instead. So the
        // delivery is awaited on the very surface under test (same convention as
        // the fold assertions above) rather than assumed to have happened.
        lateinit var response: CellState
        awaitUntil("the wired default answers the counter's post-increment total") {
            response = state(counter.ref)
            response.kind == CellState.SNAPSHOT && response.value.toString() == "5"
        }

        response.kind shouldBe CellState.SNAPSHOT
        response.value.toString() shouldBe "5"
        response.frontier shouldBe null
    }

    @Test
    fun `a snapshot read that misses the bounded wait answers unavailable, not a hang`() {
        val slow = SlowSnapshotCell().also { host.managementInlet.call.spawn(it) }

        // SlowSnapshotCell.snapshot() blocks past InspectorServer's bounded
        // wait on the host's own thread; the HTTP thread gets the honest "no
        // fallback" answer within its timeout instead of hanging on it
        state(slow.ref).kind shouldBe CellState.UNAVAILABLE
    }

    // -------------------------------------------------------------- fixtures

    /**
     * A `Stateful` cell whose `snapshot()` blocks well past
     * [InspectorServer.SNAPSHOT_WAIT_MS] — the fixture for proving the
     * bounded wait actually bounds it, rather than merely existing in code.
     * No outlet at all, so it is also unobservable the ordinary way; that is
     * incidental here, not what this fixture tests.
     */
    private class SlowSnapshotCell(override val ref: CellRef = CellRef(java.util.UUID.randomUUID())) :
        Cell, Stateful {
        override fun snapshot(): Serializable {
            Thread.sleep(SLOW_SNAPSHOT_MS)
            return 0L
        }

        override fun restore(state: Serializable) = Unit

        companion object {
            /** Comfortably past [InspectorServer.SNAPSHOT_WAIT_MS] (200ms). */
            const val SLOW_SNAPSHOT_MS = 1_000L
        }
    }

    // ------------------------------------------------------------- helpers

    /** The refs this cell's outlet feeds that are not the cell itself — its observation sinks. */
    private fun sinks(ref: CellRef): Set<CellRef> =
        registry.swapSet(ref).mapNotNull { it.to.cell }.filterTo(mutableSetOf()) { it != ref }

    private fun awaitSink(ref: CellRef): CellRef {
        awaitUntil("an observation sink linked to $ref") { sinks(ref).isNotEmpty() }
        return sinks(ref).single()
    }

    private fun listen(): SseTap {
        val opened = SseTap("http://localhost:${server.boundPort}${InspectorServer.EVENTS_PATH}")
        tap = opened
        awaitUntil("sse client attached", timeoutMs = 5_000) { server.attachedClients > 0 }
        return opened
    }

    private data class Frame(val seq: Long, val kind: String, val payload: JsonObject)

    private inner class SseTap(url: String) : AutoCloseable {
        private val frames = LinkedBlockingQueue<Frame>()

        /**
         * Held so [close] can release it (computenet-4vh): one client per
         * `listen()`, i.e. per test method, each with its own selector thread and
         * executor pool; cancelling [reader] alone left all of that alive.
         */
        private val client: HttpClient = HttpClient.newHttpClient()
        private val reader: CompletableFuture<Void> = client
            .sendAsync(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofLines())
            .thenAccept { response ->
                response.body().forEach { line ->
                    if (line.startsWith(DATA)) {
                        val event = json.parseToJsonElement(line.removePrefix(DATA)).jsonObject
                        frames += Frame(
                            seq = event["seq"]!!.jsonPrimitive.content.toLong(),
                            kind = event["kind"]!!.jsonPrimitive.content,
                            payload = event["payload"]!!.jsonObject,
                        )
                    }
                }
            }

        fun awaitAtLeast(count: Int): List<Frame> {
            awaitUntil("$count sse frames (saw ${frames.size})", timeoutMs = 10_000) { frames.size >= count }
            return frames.toList()
        }

        fun countOfKind(kind: String): Int = frames.count { it.kind == kind }

        /** Every payload of [kind] received so far, in arrival order — no waiting. */
        fun payloadsOf(kind: String): List<JsonObject> = frames.filter { it.kind == kind }.map { it.payload }

        fun awaitKind(kind: String, count: Int): List<JsonObject> {
            awaitUntil("$count '$kind' frames", timeoutMs = 10_000) {
                frames.count { it.kind == kind } >= count
            }
            return frames.filter { it.kind == kind }.map { it.payload }
        }

        /**
         * `shutdownNow()`, never `close()`: this client is deliberately parked on
         * an SSE response that never ends, so `close()` — which awaits
         * termination of in-flight exchanges — would turn this teardown into the
         * unbounded wait the suite is being audited for.
         */
        override fun close() {
            reader.cancel(true)
            client.shutdownNow()
        }
    }

    private companion object {
        const val DATA = "data: "
    }
}
