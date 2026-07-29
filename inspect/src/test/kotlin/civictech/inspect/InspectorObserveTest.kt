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
    private val host = ManagedHost(registry = registry)
    private val server = InspectorServer(registry, mapOf("test-host" to host), port = 0).start()
    private val probe = HttpProbe("http://localhost:${server.boundPort}")
    private var tap: SseTap? = null

    @AfterEach
    fun tearDown() {
        tap?.close()
        server.close()
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
        // SetCell is Stateful, so the wired snapshot fallback now answers its
        // raw state here instead of CellState.UNAVAILABLE; that fallback read
        // is host-routed and one-shot, not an observation.
        probe.get("${InspectorServer.CELL_PATH}/${InspectorServer.encodeRef(cell.ref)}").statusCode() shouldBe 200
        state(cell.ref).kind shouldBe CellState.SNAPSHOT
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
        // released, not gone: the wired snapshot fallback still answers this
        // Stateful cell's state, exactly as it did before it was ever observed
        state(cell.ref).kind shouldBe CellState.SNAPSHOT
    }

    @Test
    fun `a released observation fires no further onChange`() {
        val cell = set()
        val events = listen()
        probe.postForm("", observePath(cell.ref)).statusCode() shouldBe 204
        val sinkRef = awaitSink(cell.ref)
        add(cell, "ada")
        // catch-up summary plus the one for this add
        events.awaitKind(Event.STATE_SUMMARY, 2)

        probe.delete(observePath(cell.ref)).statusCode() shouldBe 204
        awaitUntil("observe sink despawned") { sinkRef !in registry.localRefs() }
        val summariesAtRelease = events.countOfKind(Event.STATE_SUMMARY)

        // the cell keeps working, and its outlet no longer reaches the sink:
        // the fold is not merely ignored, it is never invoked again
        val deadLettersBefore = host.supervisionAccounting().deadLetters
        add(cell, "grace")
        // the add is observable through a *fresh* subscription, so waiting on
        // it is a real barrier rather than a sleep-and-hope
        probe.postForm("", observePath(cell.ref)).statusCode() shouldBe 204
        awaitUntil("the post-release add landed in the cell") {
            state(cell.ref).value.toString() == """["ada","grace"]"""
        }

        // the released sink contributed nothing to that: every summary since
        // the release belongs to the new subscription, and no delivery to the
        // despawned sink dead-lettered
        events.countOfKind(Event.STATE_SUMMARY) shouldBe summariesAtRelease + 1
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
    fun `an open subscription streams state summaries, and only for the subscribed cell`() {
        val observed = set()
        val ignored = set()
        val events = listen()

        probe.postForm("", observePath(observed.ref)).statusCode() shouldBe 204
        // the subscription's own late-join catch-up is the first summary
        val first = events.awaitKind(Event.STATE_SUMMARY, 1).single()
        first["ref"]!!.jsonPrimitive.content shouldBe InspectorServer.encodeRef(observed.ref)
        first["cardinality"]!!.jsonPrimitive.content shouldBe "0 rows"

        add(observed, "ada")
        add(ignored, "noise")

        val summaries = events.awaitKind(Event.STATE_SUMMARY, 2)
        summaries.size shouldBe 2
        summaries.map { it["ref"]!!.jsonPrimitive.content }.toSet() shouldBe
            setOf(InspectorServer.encodeRef(observed.ref))
        val latest = summaries.last()
        latest["cardinality"]!!.jsonPrimitive.content shouldBe "1 row"
        latest["frontier"]!!.jsonObject["counter"]!!.jsonPrimitive.content shouldBe "1"
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
        // stands in for the kernel accessor M1 does not have (see SnapshotSource):
        // what matters here is that the reader encodes and labels it correctly
        server.snapshots = SnapshotSource { ref -> if (ref == cell.ref) cell.snapshot() else null }

        val state = state(cell.ref)

        state.kind shouldBe CellState.SNAPSHOT
        state.value.toString() shouldBe """["ada"]"""
        state.frontier shouldBe null
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

        val response = state(counter.ref)

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
        private val reader: CompletableFuture<Void> = HttpClient.newHttpClient()
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

        fun awaitKind(kind: String, count: Int): List<JsonObject> {
            awaitUntil("$count '$kind' frames", timeoutMs = 10_000) {
                frames.count { it.kind == kind } >= count
            }
            return frames.filter { it.kind == kind }.map { it.payload }
        }

        override fun close() {
            reader.cancel(true)
        }
    }

    private companion object {
        const val DATA = "data: "
    }
}
