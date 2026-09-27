package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.link.LinkResult
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WKB2 F6 task 2 (wczst-D5, wczst-D6) — the observer half, model-side only:
 * [Node.staged] / [GraphSummary.staged], [InspectorModel.applyEvent] and
 * [InspectorModel.restamp], and [Graphs.list]'s live/staged counts. No
 * applier exists yet (task 3): the `staged` supplier is driven directly, as
 * the design's task breakdown says to.
 *
 * [InspectorModel] is `internal`; constructed directly here rather than
 * through [InspectorServer] — that constructor does not yet accept a
 * `staged` supplier (task 3 wires it to `StagedApplier::stagedApplyOf`).
 * Registry hooks are wired by hand, exactly as [InspectorServer.hooks] wires
 * them, since building the model directly skips that server-owned wiring.
 */
class InspectorStagedMarkingTest {

    private val json = Json { ignoreUnknownKeys = false }
    private val registry = LocationRegistry()
    private val hostRef = CellRef(UUID.randomUUID())
    private val hostScheduler = VirtualThreadScheduler("ManagedHost-${hostRef.id}")
    private val host = ManagedHost(ref = hostRef, scheduler = hostScheduler, registry = registry)

    private val frames = mutableListOf<Event>()

    /** Flipped mid-test to drive [InspectorModel]'s `staged` supplier. */
    private var stagedOf: (CellRef) -> String? = { null }

    private val model = InspectorModel(
        registry = registry,
        hosts = mapOf("test-host" to host),
        cellNames = emptyMap(),
        emit = { raw -> frames += json.decodeFromString<Event>(raw) },
        staged = { ref -> stagedOf(ref) },
    )

    private val hooks: List<AutoCloseable> = listOf(
        registry.onLocalPublish(model::published),
        registry.onTopology(model::linked, model::unlinked),
        registry.onUnpublish(model::unpublished),
    )

    @AfterEach
    fun tearDown() {
        hooks.forEach { runCatching { it.close() } }
        hostScheduler.shutdown()
    }

    private fun spawn(): CellRef =
        SetCell<String>().also { host.managementInlet.call.spawn(it) }.ref

    private fun connect(from: CellRef, to: CellRef) {
        (host.managementInlet.call.connect(from, "outlet", to, "deltaInlet") as LinkResult.Connected)
    }

    // -------------------------------------------------------------- snapshot

    @Test
    fun `snapshot and components carry the staged mark for exactly the marked ref`() {
        val a = spawn()
        val b = spawn()
        connect(a, b)
        stagedOf = { ref -> if (ref == b) "apply-1" else null }

        val snapshot = model.snapshot()
        snapshot.nodes.first { it.ref == encoded(a) }.staged shouldBe null
        snapshot.nodes.first { it.ref == encoded(b) }.staged shouldBe StagedMark("apply-1")

        val summary = Graphs.list(model.components(), ErrorSnapshot(ErrorCounters(0, 0, 0, 0), emptyList(), emptyList(), emptyList())).graphs.single()
        summary.cells shouldBe 1
        summary.staged shouldBe 1

        stagedOf = { null }
        val settled = Graphs.list(model.components(), ErrorSnapshot(ErrorCounters(0, 0, 0, 0), emptyList(), emptyList(), emptyList())).graphs.single()
        settled.cells shouldBe 2
        settled.staged shouldBe 0
        model.snapshot().nodes.forEach { it.staged shouldBe null }
    }

    // ---------------------------------------------------------------- restamp

    @Test
    fun `restamp emits one upsert with the current stamp on the next seq, and is a no-op for an unknown ref`() {
        val a = spawn()
        val b = spawn()
        connect(a, b)
        stagedOf = { ref -> if (ref == b) "apply-1" else null }
        val beforeSeq = model.snapshot().seq
        frames.clear()

        model.restamp(b)

        frames.map { it.kind } shouldBe listOf(Event.TOPOLOGY_NODE)
        val frame = frames.single()
        frame.seq shouldBe beforeSeq + 1
        frame.payload["op"]?.jsonPrimitive?.content shouldBe Event.ADDED
        val node = json.decodeFromJsonElement(Node.serializer(), frame.payload.getValue("node"))
        node.ref shouldBe encoded(b)
        node.staged shouldBe StagedMark("apply-1")

        frames.clear()
        val unknownRef = CellRef(UUID.randomUUID())
        val seqBefore = model.snapshot().seq

        model.restamp(unknownRef)

        frames shouldBe emptyList()
        model.snapshot().seq shouldBe seqBefore
    }

    /**
     * Feature review (computenet-wczst): a staged↔live flip changes the
     * navigator card ([GraphSummary.cells] / [GraphSummary.staged]) without
     * moving component membership, so [InspectorModel.publishGraphChanges]'
     * membership comparison alone never announces it — a client that refetched
     * `GET /graphs` during STAGE kept `cells: 0, staged: n` after RETIRE.
     */
    @Test
    fun `a restamp invalidates the navigator cards on the next graphs tick`() {
        val a = spawn()
        val b = spawn()
        connect(a, b)
        model.publishGraphChanges()
        frames.clear()
        model.publishGraphChanges()
        frames shouldBe emptyList()

        stagedOf = { ref -> if (ref == b) "apply-1" else null }
        model.restamp(b)
        frames.clear()
        model.publishGraphChanges()
        frames.map { it.kind } shouldBe listOf(Event.GRAPHS_CHANGED)

        stagedOf = { null }
        model.restamp(b)
        frames.clear()
        model.publishGraphChanges()
        frames.map { it.kind } shouldBe listOf(Event.GRAPHS_CHANGED)

        frames.clear()
        model.publishGraphChanges()
        frames shouldBe emptyList()
    }

    // ------------------------------------------------------------- applyEvent

    @Test
    fun `applyEvent shares the model's monotonic seq with restamp`() {
        val a = spawn()
        val b = spawn()
        connect(a, b)
        val afterSetupSeq = model.snapshot().seq
        frames.clear()

        model.applyEvent(Event.APPLY_PHASE, buildJsonObject {
            put("applyId", "apply-1")
            put("phase", "STAGE")
        })

        val first = frames.single()
        first.kind shouldBe Event.APPLY_PHASE
        first.seq shouldBe afterSetupSeq + 1

        stagedOf = { ref -> if (ref == b) "apply-1" else null }
        model.restamp(b)

        val second = frames[1]
        second.kind shouldBe Event.TOPOLOGY_NODE
        second.seq shouldBe first.seq + 1
    }

    // ------------------------------------------------------------ serialization

    @Test
    fun `a live node encodes staged null, a marked one its applyId, and both decode`() {
        val a = spawn()
        stagedOf = { null }
        val liveJson = inspectorJson.encodeToString(model.snapshot().nodes.single { it.ref == encoded(a) })
        liveJson shouldContainJson "\"staged\":null"

        stagedOf = { ref -> if (ref == a) "apply-9" else null }
        val markedJson = inspectorJson.encodeToString(model.snapshot().nodes.single { it.ref == encoded(a) })
        markedJson shouldContainJson "\"staged\":{\"applyId\":\"apply-9\"}"

        // fixture compatibility: a Node JSON with no `staged` key still decodes
        val withoutStagedKey = liveJson.replace(Regex(",\"staged\":null"), "")
        val decoded = json.decodeFromString<Node>(withoutStagedKey)
        decoded.staged shouldBe null

        val summaryJson = inspectorJson.encodeToString(
            GraphSummary(id = "g-x", cells = 1, hosts = 1, nets = 1, health = GraphHealth(0, 0, 0)),
        )
        summaryJson shouldContainJson "\"staged\":0"
    }

    private infix fun String.shouldContainJson(fragment: String) {
        (fragment in this) shouldBe true
    }

    private fun encoded(ref: CellRef): String = InspectorServer.encodeRef(ref)
}
