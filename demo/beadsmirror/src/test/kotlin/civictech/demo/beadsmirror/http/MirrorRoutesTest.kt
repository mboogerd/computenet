package civictech.demo.beadsmirror.http

import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.EdgeDiff
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.FieldDiff
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorProjector
import civictech.demo.shell.DemoShell
import civictech.testkit.HttpProbe
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * computenet-dqj.4.1: [MirrorRoutes] serves [MirrorProjector.view] and
 * [MirrorProjector.edgeView] as JSON through [DemoShell].
 *
 * No `bd`/`dolt` — [ChangeRecord]s are hand-built the same way
 * `MirrorProjectorTest`/`MirrorEdgeProjectionTest` build them, applied to a
 * real [MirrorProjector], and read back over a real loopback HTTP round trip
 * (`DemoShell(0)`), matching this task's prescribed test shape.
 */
class MirrorRoutesTest {

    private val minter = DotMinter("beads-scratch-42")
    private lateinit var projector: MirrorProjector
    private lateinit var shell: DemoShell
    private lateinit var probe: HttpProbe

    @BeforeEach
    fun start() {
        projector = MirrorProjector(minter)
        shell = DemoShell(0)
        MirrorRoutes(projector).register(shell)
        shell.start()
        probe = HttpProbe("http://localhost:${shell.boundPort}")
    }

    @AfterEach
    fun stop() {
        probe.close()
        shell.stop()
    }

    /** A record of one commit's changes to one issue. `null` field value = cleared. */
    private fun issueRecord(
        height: Long,
        issue: String,
        type: DiffType?,
        vararg fields: Pair<String, String?>,
        edges: List<EdgeDiff> = emptyList(),
        ordinal: Int = 0,
    ) = ChangeRecord(
        commitHash = "commit-$height",
        position = FeedPosition(height, ordinal),
        issueId = issue,
        diffType = type,
        fieldDiffs = fields.map { (column, value) ->
            FieldDiff(column, old = null, new = value?.let(::JsonPrimitive))
        },
        edgeDiffs = edges,
    )

    // -----------------------------------------------------------------
    // rule 1/2 — list route, presence-gated
    // -----------------------------------------------------------------

    @Test
    fun `the list route serves a present issue's fields and omits an absent one with a stale field key`() {
        // A: present, 2 projected fields (view() gates on the presence key
        // without echoing it back as a field — MirrorProjector.view()).
        projector.apply(issueRecord(1, "A", DiffType.ADDED, "title" to "Alpha", "status" to "open"))
        // B: created then removed — presence key tombstoned; a replay-style
        // straggler field key would remain live were the gate not presence-only,
        // which is exactly what `view()` (and so this route) must not surface.
        projector.apply(issueRecord(2, "B", DiffType.ADDED, "title" to "Beta"))
        projector.apply(issueRecord(3, "B", DiffType.REMOVED))

        val body = Json.parseToJsonElement(probe.get("/beads/issues").body()).jsonObject

        body.keys shouldBe setOf("A")
        val a = body.getValue("A").jsonObject
        a.keys shouldBe setOf("title", "status")
        a["title"]?.jsonPrimitive?.content shouldBe "Alpha"
        a["status"]?.jsonPrimitive?.content shouldBe "open"
    }

    // -----------------------------------------------------------------
    // rule 3 — single-issue route carries its dependency edges
    // -----------------------------------------------------------------

    @Test
    fun `the single-issue route includes fields and this issue's owning-side dependency edges`() {
        projector.apply(issueRecord(1, "A", DiffType.ADDED, "title" to "Alpha"))
        projector.apply(issueRecord(2, "B", DiffType.ADDED, "title" to "Beta"))
        // bd dep add B A: B depends on A -> GET B carries the edge, GET A does not
        projector.apply(issueRecord(3, "B", null, edges = listOf(EdgeDiff(DiffType.ADDED, "B", "A", "blocks"))))

        val response = probe.get("/beads/issues/B")
        response.statusCode() shouldBe 200
        val body = Json.parseToJsonElement(response.body()).jsonObject
        body["title"]?.jsonPrimitive?.content shouldBe "Beta"
        val deps = body.getValue("dependencies").jsonArray
        deps.size shouldBe 1
        val edge = deps.single().jsonObject
        edge["issue_id"]?.jsonPrimitive?.content shouldBe "B"
        edge["depends_on_issue_id"]?.jsonPrimitive?.content shouldBe "A"
        edge["type"]?.jsonPrimitive?.content shouldBe "blocks"

        val other = Json.parseToJsonElement(probe.get("/beads/issues/A").body()).jsonObject
        other.getValue("dependencies").jsonArray.size shouldBe 0
    }

    @Test
    fun `the single-issue route 404s on an id whose presence key is absent`() {
        projector.apply(issueRecord(1, "B", DiffType.ADDED, "title" to "Beta"))
        projector.apply(issueRecord(2, "B", DiffType.REMOVED))

        probe.get("/beads/issues/B").statusCode() shouldBe 404
        probe.get("/beads/issues/never-existed").statusCode() shouldBe 404
    }

    // -----------------------------------------------------------------
    // read-after-apply
    // -----------------------------------------------------------------

    @Test
    fun `a route read after the projector applies a further record reflects it, no restart`() {
        projector.apply(issueRecord(1, "A", DiffType.ADDED, "title" to "Alpha"))

        Json.parseToJsonElement(probe.get("/beads/issues").body()).jsonObject.keys shouldBe setOf("A")

        projector.apply(issueRecord(2, "B", DiffType.ADDED, "title" to "Beta"))

        val body = Json.parseToJsonElement(probe.get("/beads/issues").body()).jsonObject
        body.keys shouldBe setOf("A", "B")
        body.getValue("B").jsonObject["title"]?.jsonPrimitive?.content shouldBe "Beta"
    }
}
