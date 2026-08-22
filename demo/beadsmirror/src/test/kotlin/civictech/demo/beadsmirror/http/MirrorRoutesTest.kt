package civictech.demo.beadsmirror.http

import civictech.demo.beadsmirror.MirrorState
import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.EdgeDiff
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.FieldDiff
import civictech.demo.beadsmirror.feed.PollLoopStopped
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
    private lateinit var state: MirrorState
    private lateinit var shell: DemoShell
    private lateinit var probe: HttpProbe

    @BeforeEach
    fun start() {
        projector = MirrorProjector(minter)
        state = MirrorState(projector)
        shell = DemoShell(0)
        MirrorRoutes(state).register(shell)
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

    // -----------------------------------------------------------------
    // computenet-dqj.3.3 — the MirrorState indirection
    // -----------------------------------------------------------------

    /**
     * The re-baseline swap, seen from the route: after [MirrorState.swap] the
     * routes must serve the NEW projector and nothing of the old one — a
     * `MirrorRoutes` that captured the projector at construction would keep
     * answering from the discarded pre-gap state forever. `A` is deliberately
     * absent from the replacement (the zombie case, feature rule 4), and its
     * edge with it.
     */
    @Test
    fun `after the state is swapped the routes serve the new projector and none of the old one`() {
        projector.apply(issueRecord(1, "A", DiffType.ADDED, "title" to "Alpha"))
        projector.apply(issueRecord(2, "A", null, edges = listOf(EdgeDiff(DiffType.ADDED, "A", "Z", "blocks"))))
        Json.parseToJsonElement(probe.get("/beads/issues").body()).jsonObject.keys shouldBe setOf("A")

        val rebuilt = MirrorProjector(DotMinter("beads-scratch-42"))
        rebuilt.apply(issueRecord(9, "C", DiffType.ADDED, "title" to "Gamma"))
        state.swap(rebuilt)

        val body = Json.parseToJsonElement(probe.get("/beads/issues").body()).jsonObject
        body.keys shouldBe setOf("C")
        body.getValue("C").jsonObject["title"]?.jsonPrimitive?.content shouldBe "Gamma"
        probe.get("/beads/issues/A").statusCode() shouldBe 404
        // The old projector's edge went with it: C's route carries no edges,
        // and A's is gone entirely.
        Json.parseToJsonElement(probe.get("/beads/issues/C").body())
            .jsonObject.getValue("dependencies").jsonArray.size shouldBe 0
        state.rebaselineCount shouldBe 1
    }

    // -----------------------------------------------------------------
    // computenet-3bso.1.2 — workspace-addressed HTTP surface
    // -----------------------------------------------------------------

    /**
     * Two workspaces sharing one shell, each served under its own
     * `/workspaces/{identity}/beads/issues` route rather than the single-fold
     * `shell`/`probe` this class's other tests use.
     */
    private class TwoWorkspaceRig(
        wsAFrozen: () -> PollLoopStopped? = { null },
        wsBFrozen: () -> PollLoopStopped? = { null },
    ) : AutoCloseable {
        val projectorA = MirrorProjector(DotMinter("wsA"))
        val projectorB = MirrorProjector(DotMinter("wsB"))
        val stateA = MirrorState(projectorA)
        val stateB = MirrorState(projectorB)
        val shell = DemoShell(0)
        val probe: HttpProbe

        init {
            MirrorRoutes(
                listOf(
                    MirrorRoutes.Workspace("wsA", stateA, wsAFrozen),
                    MirrorRoutes.Workspace("wsB", stateB, wsBFrozen),
                ),
            ).register(shell)
            shell.start()
            probe = HttpProbe("http://localhost:${shell.boundPort}")
        }

        override fun close() {
            probe.close()
            shell.stop()
        }
    }

    @Test
    fun `GET workspaces lists every configured workspace identity`() {
        TwoWorkspaceRig().use { rig ->
            val body = Json.parseToJsonElement(rig.probe.get("/workspaces").body())
            body.jsonArray.map { it.jsonPrimitive.content }.toSet() shouldBe setOf("wsA", "wsB")
        }
    }

    @Test
    fun `a workspace-segmented route serves that workspace's fold and none of its sibling's`() {
        TwoWorkspaceRig().use { rig ->
            rig.projectorA.apply(issueRecord(1, "A1", DiffType.ADDED, "title" to "Alpha One"))
            rig.projectorB.apply(issueRecord(1, "B1", DiffType.ADDED, "title" to "Beta One"))

            val fromA = Json.parseToJsonElement(rig.probe.get("/workspaces/wsA/beads/issues").body()).jsonObject
            fromA.keys shouldBe setOf("A1")

            val fromB = Json.parseToJsonElement(rig.probe.get("/workspaces/wsB/beads/issues").body()).jsonObject
            fromB.keys shouldBe setOf("B1")

            val single = rig.probe.get("/workspaces/wsA/beads/issues/A1")
            single.statusCode() shouldBe 200
            Json.parseToJsonElement(single.body()).jsonObject["title"]?.jsonPrimitive?.content shouldBe "Alpha One"
        }
    }

    @Test
    fun `a request naming an unknown workspace identity answers a plain 404, not the frozen envelope`() {
        TwoWorkspaceRig().use { rig ->
            val response = rig.probe.get("/workspaces/wsZ/beads/issues")
            response.statusCode() shouldBe 404
            val body = Json.parseToJsonElement(response.body()).jsonObject
            // Plain 404: no "mirror":"frozen" envelope — an unknown workspace
            // is a config fact, never a frozen-fold fact.
            body.keys shouldBe setOf("error")
        }
    }

    @Test
    fun `one workspace's dead poll loop answers 503 on its own routes while its sibling stays 200`() {
        val frozen = PollLoopStopped(RuntimeException("dolt vanished"), checkpoint = "commit-9")
        TwoWorkspaceRig(wsAFrozen = { frozen }).use { rig ->
            rig.projectorA.apply(issueRecord(1, "A1", DiffType.ADDED, "title" to "Alpha One"))
            rig.projectorB.apply(issueRecord(1, "B1", DiffType.ADDED, "title" to "Beta One"))

            val fromA = rig.probe.get("/workspaces/wsA/beads/issues")
            fromA.statusCode() shouldBe 503
            val frozenBody = Json.parseToJsonElement(fromA.body()).jsonObject
            frozenBody["mirror"]?.jsonPrimitive?.content shouldBe "frozen"
            frozenBody["frozen_at_checkpoint"]?.jsonPrimitive?.content shouldBe "commit-9"

            val fromB = rig.probe.get("/workspaces/wsB/beads/issues")
            fromB.statusCode() shouldBe 200
            Json.parseToJsonElement(fromB.body()).jsonObject.keys shouldBe setOf("B1")
        }
    }

    @Test
    fun `a two-workspace process registers no legacy unsegmented beads issues route`() {
        TwoWorkspaceRig().use { rig ->
            // The legacy path is bound to "the sole workspace" only when there
            // is one; with two hosted, DemoShell has no context registered at
            // the bare "/beads/issues" prefix, so the request 404s at the
            // server level rather than resolving to either fold.
            rig.probe.get("/beads/issues").statusCode() shouldBe 404
        }
    }
}
