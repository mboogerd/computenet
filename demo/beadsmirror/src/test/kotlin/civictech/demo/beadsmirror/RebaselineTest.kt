package civictech.demo.beadsmirror

import civictech.cell.graph.DespawnStep
import civictech.cell.graph.GraphSpec
import civictech.cell.host.DurableInput
import civictech.demo.beadsmirror.baseline.BaselineBuilder
import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.baseline.EmptyExportRefused
import civictech.demo.beadsmirror.baseline.ExportRow
import civictech.demo.beadsmirror.baseline.MirrorEvent
import civictech.demo.beadsmirror.baseline.Rebaseline
import civictech.demo.beadsmirror.baseline.RebaselineReason
import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffQuery
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.feed.DoltFeedPoller
import civictech.demo.beadsmirror.feed.DurableFeedCursor
import civictech.demo.beadsmirror.feed.FeedCondition
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.FieldDiff
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorEdge
import civictech.demo.beadsmirror.projector.MirrorProjector
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** a3v8u-D4/D6: baselines are durable-input commits into a real solo [MirrorGraph]. */
class RebaselineTest {

    private lateinit var runDir: Path
    private val events = mutableListOf<MirrorEvent>()

    @BeforeEach
    fun setUp() {
        runDir = Files.createTempDirectory("rebaseline-run-")
        events.clear()
    }

    @AfterEach
    fun tearDown() {
        if (::runDir.isInitialized) runDir.toFile().deleteRecursively()
    }

    @Test
    fun `first start fills the live hosted projector without swapping and commits its head`() {
        val rig = rig()

        rig.rebaseline(
            rows = listOf(row("B", "title" to "Beta"), row("C", "title" to "Gamma", dependsOn = "B")),
            history = listOf("c0", "head"),
        ).run(RebaselineReason.FirstStart)

        rig.state.current shouldBe rig.initial
        rig.state.rebaselineCount shouldBe 0
        rig.state.current.view().keys shouldBe setOf("B", "C")
        rig.state.current.edgeView() shouldBe setOf(MirrorEdge("C", "B", "blocks"))
        rig.input.committed() shouldBe "head"
    }

    @Test
    fun `checkpoint-gone swaps both cells under the same refs and no pre-gap issue survives`() {
        val rig = rig()
        rig.initial.apply(createRecord(11, "B", "status", "open"))
        rig.initial.apply(createRecord(11, "ZOMBIE", "title", "old", ordinal = 1))
        rig.graph.host.quiescence().await(30_000, "pre-gap records")
        val incumbent = rig.initial

        rig.rebaseline(
            rows = listOf(row("B", "status" to "closed")),
            history = listOf("flat0", "flat1"),
        ).run(RebaselineReason.CheckpointGone("pre-gap"))

        (rig.state.current === incumbent) shouldBe false
        rig.state.current.cell.ref shouldBe incumbent.cell.ref
        rig.state.current.edges.ref shouldBe incumbent.edges.ref
        rig.state.current.view() shouldBe mapOf(
            "B" to mapOf("id" to "\"B\"", "status" to "\"closed\""),
        )
        rig.state.rebaselineCount shouldBe 1
        rig.input.committed() shouldBe "flat1"
    }

    @Test
    fun `a throwing durable-input drive leaves the swapped projector empty and cursor unchanged`() {
        val rig = rig()
        rig.input.commit { "old-head" }
        val applied = rig.graph.apply(
            GraphSpec(
                listOf(
                    DespawnStep(MirrorGraph.MAP_HANDLE),
                    DespawnStep(MirrorGraph.EDGES_HANDLE),
                ) + rig.graph.spec().steps,
            ),
        )
        val target = rig.graph.projector(DotMinter(IDENTITY), applied)
        rig.state.swap(target)
        val records = BaselineBuilder(DotMinter(IDENTITY)).records(
            listOf(row("B", "title" to "Beta")),
            "new-head",
            1,
        )

        shouldThrowAny {
            rig.graph.input(applied).commit {
                target.applyAll(records)
                error("refuse this input")
            }
        }
        rig.graph.host.quiescence().await(30_000, "refused baseline")

        rig.graph.input().committed() shouldBe "old-head"
        target.view() shouldBe emptyMap()
    }

    @Test
    fun `a commit landing during export remains after the captured cursor`() {
        val rig = rig()
        val log = mutableListOf("c0", "head")
        Rebaseline(
            export = {
                listOf(row("B", "title" to "Beta")).also { log += "concurrent" }
            },
            feed = DoltCommitFeed(fakeLog(log)),
            graph = rig.graph,
            state = rig.state,
            input = { rig.graph.input() },
            workspaceIdentity = IDENTITY,
            onEvent = events::add,
        ).run(RebaselineReason.FirstStart)

        rig.input.committed() shouldBe "head"
        (events.single() as MirrorEvent.Rebaselined).headCommit shouldBe "head"
    }

    @Test
    fun `an empty export after first start is refused without moving fold or cursor`() {
        val rig = rig()
        rig.rebaseline(listOf(row("A", "title" to "Alpha")), listOf("c0", "pre"))
            .run(RebaselineReason.FirstStart)
        val populated = rig.state.current

        val refusal = shouldThrow<EmptyExportRefused> {
            rig.rebaseline(emptyList(), listOf("c0", "head"))
                .run(RebaselineReason.CheckpointGone("pre"))
        }

        refusal.reason shouldBe RebaselineReason.CheckpointGone("pre")
        rig.state.current shouldBe populated
        rig.state.current.view().keys shouldBe setOf("A")
        rig.input.committed() shouldBe "pre"
    }

    @Test
    fun `a first start accepts an empty export`() {
        val rig = rig()

        rig.rebaseline(emptyList()).run(RebaselineReason.FirstStart)

        rig.state.current.view() shouldBe emptyMap()
        rig.input.committed() shouldBe "head"
        (events.single() as MirrorEvent.Rebaselined).issueCount shouldBe 0
    }

    @Test
    fun `the explicit override accepts an empty replacement`() {
        val rig = rig()
        rig.rebaseline(listOf(row("A", "title" to "Alpha")), listOf("c0", "pre"))
            .run(RebaselineReason.FirstStart)

        rig.rebaseline(emptyList(), acceptEmptyExport = true)
            .run(RebaselineReason.CheckpointGone("pre"))

        rig.state.current.view() shouldBe emptyMap()
        rig.input.committed() shouldBe "head"
    }

    @Test
    fun `history-merged swaps from the export and reports the typed reason`() {
        val rig = rig()
        rig.rebaseline(listOf(row("A", "title" to "stale")), listOf("c0", "pre-pull"))
            .run(RebaselineReason.FirstStart)
        events.clear()

        rig.rebaseline(
            rows = listOf(row("A", "title" to "Alpha"), row("P", "title" to "Peer")),
            history = listOf("c0", "pre-pull", MERGE),
        ).run(RebaselineReason.HistoryMerged(MERGE))

        rig.state.current.view().keys shouldBe setOf("A", "P")
        rig.input.committed() shouldBe MERGE
        events shouldBe listOf(
            MirrorEvent.Rebaselined(RebaselineReason.HistoryMerged(MERGE), MERGE, 2, IDENTITY),
        )
    }

    @Test
    fun `a failing export under merged history stops the poller without moving state or cursor`() {
        val rig = rig()
        rig.rebaseline(listOf(row("A", "title" to "Alpha")), listOf("c0", "pre-pull"))
            .run(RebaselineReason.FirstStart)
        events.clear()
        val prePull = rig.state.current
        val feed = DoltCommitFeed(mergedLog(listOf("c0", "pre-pull", MERGE)))
        val exportFailure = IllegalStateException("bd export exited 1")
        val rebaseline = Rebaseline(
            export = { throw exportFailure },
            feed = feed,
            graph = rig.graph,
            state = rig.state,
            input = { rig.graph.input() },
            workspaceIdentity = IDENTITY,
            onEvent = events::add,
        )
        val cursor = DurableFeedCursor(rig.graph.input(), rig.graph.host, "merged-history failure")
        val poller = DoltFeedPoller(
            feed = feed,
            cursor = cursor,
            interval = Duration.ofMillis(5),
            onBatch = { rig.state.current.applyAll(it) },
            onCondition = { condition ->
                when (condition) {
                    is FeedCondition.CheckpointGone ->
                        rebaseline.run(RebaselineReason.CheckpointGone(condition.checkpoint))
                    is FeedCondition.HistoryMerged ->
                        rebaseline.run(RebaselineReason.HistoryMerged(condition.mergeCommit))
                }
            },
        )

        poller.use {
            poller.start()
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (poller.stopped == null && System.nanoTime() < deadline) Thread.sleep(10)
            poller.stopped.shouldNotBeNull().failure shouldBe exportFailure
        }

        rig.state.current shouldBe prePull
        rig.state.current.view().keys shouldBe setOf("A")
        cursor.committed() shouldBe "pre-pull"
        events shouldBe emptyList()
    }

    @Nested
    inner class AgainstAScratchWorkspace {
        private lateinit var workspace: BdScratchWorkspace

        @BeforeEach
        fun setUpWorkspace() {
            assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
            assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
            workspace = BdScratchWorkspace.create()
        }

        @AfterEach
        fun tearDownWorkspace() {
            if (::workspace.isInitialized) workspace.close()
        }

        @Test
        fun `a real export commits the captured head and leaves the feed resumed empty`() {
            val a = workspace.createIssue("Issue A")
            val b = workspace.createIssue("Issue B")
            workspace.run("dep", "add", a, b, "--type", "blocks")
            val rig = rig()
            val feed = DoltCommitFeed(workspace.doltRoot)

            Rebaseline(
                export = BdExportReader(workspace.root)::read,
                feed = feed,
                graph = rig.graph,
                state = rig.state,
                input = { rig.graph.input() },
                workspaceIdentity = IDENTITY,
                onEvent = events::add,
            ).run(RebaselineReason.FirstStart)

            rig.state.current.view().keys shouldBe setOf(a, b)
            rig.state.current.edgeView() shouldBe setOf(MirrorEdge(a, b, "blocks"))
            val head = rig.input.committed() as String
            head shouldBe feed.history().last()
            feed.readFrom(head) shouldBe emptyList()
        }
    }

    private inner class Rig(
        val graph: MirrorGraph,
        val initial: MirrorProjector,
        val state: MirrorState,
        val input: DurableInput,
    ) {
        fun rebaseline(
            rows: List<ExportRow>,
            history: List<String> = listOf("c0", "c1", "head"),
            acceptEmptyExport: Boolean = false,
        ) = Rebaseline(
            export = { rows },
            feed = DoltCommitFeed(fakeLog(history)),
            graph = graph,
            state = state,
            input = { graph.input() },
            workspaceIdentity = IDENTITY,
            onEvent = events::add,
            acceptEmptyExport = acceptEmptyExport,
        )
    }

    private fun rig(): Rig {
        val graph = MirrorGraph.solo(runDir, IDENTITY)
        check(!graph.recovered)
        val applied = graph.apply(graph.spec())
        val initial = graph.projector(DotMinter(IDENTITY), applied)
        return Rig(graph, initial, MirrorState(initial), graph.input(applied))
    }

    private fun createRecord(
        height: Long,
        issue: String,
        field: String,
        value: String,
        ordinal: Int = 0,
    ) = ChangeRecord(
        commitHash = "commit-$height",
        position = FeedPosition(height, ordinal),
        issueId = issue,
        diffType = DiffType.ADDED,
        fieldDiffs = listOf(FieldDiff(field, old = null, new = JsonPrimitive(value))),
        edgeDiffs = emptyList(),
    )

    private fun mergedLog(history: List<String>) = DiffQuery { sql ->
        if (!sql.contains("dolt_log")) emptyList()
        else history.mapIndexed { index, hash ->
            val firstParent = if (index == 0) "" else history[index - 1]
            val parents = if (hash == MERGE) "$PEER_PARENT, $firstParent" else firstParent
            mapOf<String, JsonElement>(
                "commit_hash" to JsonPrimitive(hash),
                "parents" to JsonPrimitive(parents),
            )
        }.asReversed()
    }

    private fun fakeLog(history: List<String>) = DiffQuery { sql ->
        if (!sql.contains("dolt_log")) emptyList()
        else history.asReversed().map { mapOf<String, JsonElement>("commit_hash" to JsonPrimitive(it)) }
    }

    private fun row(id: String, vararg fields: Pair<String, String>, dependsOn: String? = null): ExportRow {
        val dependencies = dependsOn?.let {
            ""","dependencies":[{"issue_id":"$id","depends_on_id":"$it","type":"blocks"}]"""
        } ?: ""
        val body = fields.joinToString(",") { (key, value) -> "\"$key\":\"$value\"" }
        return ExportRow(id, Json.parseToJsonElement("""{"id":"$id",$body$dependencies}""") as JsonObject)
    }

    private companion object {
        const val IDENTITY = "rebaseline_scratch"
        const val MERGE = "merge-commit-hash"
        const val PEER_PARENT = "peer-parent-hash"

        fun BdScratchWorkspace.createIssue(title: String): String {
            val output = run("create", title, "--json")
            return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(output)!!.groupValues[1]
        }

        fun commandAvailable(vararg command: String): Boolean = try {
            ProcessBuilder(*command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
