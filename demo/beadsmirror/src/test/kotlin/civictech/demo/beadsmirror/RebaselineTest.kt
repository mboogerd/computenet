package civictech.demo.beadsmirror

import civictech.demo.beadsmirror.baseline.BaselineBuilder
import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.baseline.ExportRow
import civictech.demo.beadsmirror.baseline.MirrorEvent
import civictech.demo.beadsmirror.baseline.Rebaseline
import civictech.demo.beadsmirror.baseline.RebaselineReason
import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffQuery
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.feed.FeedCheckpoint
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.FieldDiff
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorEdge
import civictech.demo.beadsmirror.projector.MirrorProjector
import io.kotest.assertions.throwables.shouldThrowAny
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

/**
 * computenet-dqj.3.3, the operation itself: [Rebaseline] rebuilds the mirror
 * from a `bd export` snapshot into a FRESH projector, swaps it into
 * [MirrorState], and only then checkpoints the head it captured.
 *
 * The wiring that *triggers* it — first start and
 * [civictech.demo.beadsmirror.feed.FeedCondition.CheckpointGone] — is
 * [BeadsMirrorAppTest]'s half. Everything here drives [Rebaseline] directly,
 * mostly on hand-built export rows through its `export` seam so no `bd` is
 * needed; [AgainstAScratchWorkspace] then runs the same operation against a
 * real workspace.
 */
class RebaselineTest {

    private lateinit var runDir: Path
    private lateinit var checkpoint: FeedCheckpoint
    private val events = mutableListOf<MirrorEvent>()

    @BeforeEach
    fun setUp() {
        runDir = Files.createTempDirectory("rebaseline-run-")
        checkpoint = FeedCheckpoint(runDir)
        events.clear()
    }

    @AfterEach
    fun tearDown() {
        if (::runDir.isInitialized) {
            runDir.toFile().setWritable(true, true)
            runDir.toFile().deleteRecursively()
        }
    }

    // -----------------------------------------------------------------
    // rules 1/4 — fresh state, swapped whole, no zombies
    // -----------------------------------------------------------------

    /**
     * Feature rule 4: the rebuild replaces state rather than merging into it.
     * `A` is in the pre-gap projector and absent from the export, so it must
     * be gone — and gone from the *edge* set too, not merely from `view()`.
     */
    @Test
    fun `the swapped-in projector holds exactly the export, with no pre-gap survivors`() {
        val stale = MirrorProjector(DotMinter(IDENTITY))
        stale.apply(
            ChangeRecord(
                commitHash = "pre-gap",
                position = FeedPosition(11, 0),
                issueId = "A",
                diffType = DiffType.ADDED,
                fieldDiffs = listOf(FieldDiff("title", old = null, new = JsonPrimitive("stale Alpha"))),
                edgeDiffs = emptyList(),
            ),
        )
        val state = MirrorState(stale)

        rebaseline(state, rows = listOf(row("B", "title" to "Beta"), row("C", "title" to "Gamma", dependsOn = "B")))
            .run(RebaselineReason.FirstStart)

        // The projector object itself was replaced, not mutated.
        (state.current === stale) shouldBe false
        state.current.view().keys shouldBe setOf("B", "C")
        state.current.view().getValue("B")["title"] shouldBe "\"Beta\""
        state.current.edgeView() shouldBe setOf(MirrorEdge("C", "B", "blocks"))
        stale.view().keys shouldBe setOf("A") // untouched, simply discarded
        state.rebaselineCount shouldBe 1
    }

    /**
     * The reason fresh cells are correctness, not hygiene: after a compaction
     * the head height restarts LOW (here 1, against a pre-gap put minted at
     * height 11). Folding the baseline into the old cell would mint dots
     * sorting below the pre-gap put and lose last-writer-wins to it, leaving
     * the stale value live; a swap cannot.
     */
    @Test
    fun `a post-compaction head lower than the pre-gap height still wins, because the cell is new`() {
        val stale = MirrorProjector(DotMinter(IDENTITY))
        stale.apply(
            ChangeRecord(
                commitHash = "pre-gap",
                position = FeedPosition(11, 0),
                issueId = "B",
                diffType = DiffType.ADDED,
                fieldDiffs = listOf(FieldDiff("status", old = null, new = JsonPrimitive("open"))),
                edgeDiffs = emptyList(),
            ),
        )
        val state = MirrorState(stale)

        rebaseline(
            state,
            rows = listOf(row("B", "status" to "closed")),
            history = listOf("flat0", "flat1"), // head height 1 — below the pre-gap 11
        ).run(RebaselineReason.CheckpointGone("pre-gap"))

        state.current.view().getValue("B")["status"] shouldBe "\"closed\""
    }

    // -----------------------------------------------------------------
    // rule 2 — the checkpoint equals the captured head
    // -----------------------------------------------------------------

    @Test
    fun `the persisted checkpoint becomes the head captured with the export`() {
        val state = MirrorState(MirrorProjector(DotMinter(IDENTITY)))

        rebaseline(state, rows = listOf(row("B", "title" to "Beta")), history = listOf("c0", "c1", "head"))
            .run(RebaselineReason.FirstStart)

        checkpoint.read() shouldBe "head"
    }

    /**
     * Ordering pin (the operation's one crash-safety rule): the swap happens
     * BEFORE the checkpoint write, so a crash between them re-runs an
     * idempotent baseline next start rather than resuming the feed over
     * pre-swap state. Driven by making the checkpoint write fail — an
     * unwritable run directory — and asserting the swap survived it.
     */
    @Test
    fun `state is swapped before the checkpoint is written, so a failed write leaves new state and no checkpoint`() {
        assumeTrue(runDir.toFile().setWritable(false, false), "could not make the run dir read-only")
        assumeTrue(!Files.isWritable(runDir), "run dir is still writable (running as root?) — skipping")

        val state = MirrorState(MirrorProjector(DotMinter(IDENTITY)))
        val rebaseline = rebaseline(state, rows = listOf(row("B", "title" to "Beta")))

        shouldThrowAny { rebaseline.run(RebaselineReason.FirstStart) }

        state.current.view().keys shouldBe setOf("B")
        state.rebaselineCount shouldBe 1
        checkpoint.read() shouldBe null
        events shouldBe emptyList() // the event reports a COMPLETED re-baseline
    }

    // -----------------------------------------------------------------
    // rule 5 — the typed event
    // -----------------------------------------------------------------

    @Test
    fun `the event carries the reason, the captured head and the issue count`() {
        val state = MirrorState(MirrorProjector(DotMinter(IDENTITY)))

        rebaseline(
            state,
            rows = listOf(row("B", "title" to "Beta"), row("C", "title" to "Gamma")),
            history = listOf("c0", "head"),
        ).run(RebaselineReason.CheckpointGone("gone-hash"))

        events shouldBe listOf(
            MirrorEvent.Rebaselined(RebaselineReason.CheckpointGone("gone-hash"), "head", 2),
        )
    }

    @Test
    fun `a first-start re-baseline is distinguishable from a checkpoint-gone one by the reason alone`() {
        val state = MirrorState(MirrorProjector(DotMinter(IDENTITY)))

        rebaseline(state, rows = listOf(row("B", "title" to "Beta"))).run(RebaselineReason.FirstStart)

        val event = events.single() as MirrorEvent.Rebaselined
        event.reason shouldBe RebaselineReason.FirstStart
        (event.reason is RebaselineReason.CheckpointGone) shouldBe false
    }

    // -----------------------------------------------------------------
    // the same operation against a real bd workspace
    // -----------------------------------------------------------------

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

        /**
         * The whole operation with a real `bd export` and a real `dolt_log`:
         * state matches the workspace, the checkpoint equals the head, and the
         * feed reads nothing new past it (feature rule 2's "subsequent polling
         * emits only records after it", at its boundary).
         */
        @Test
        fun `rebuilds a real workspace and checkpoints a head the feed then resumes empty from`() {
            val a = workspace.createIssue("Issue A")
            val b = workspace.createIssue("Issue B")
            workspace.run("dep", "add", a, b, "--type", "blocks")

            val feed = DoltCommitFeed(workspace.doltRoot)
            val state = MirrorState(MirrorProjector(DotMinter(IDENTITY)))
            Rebaseline(
                export = BdExportReader(workspace.root)::read,
                feed = feed,
                checkpoint = checkpoint,
                state = state,
                workspaceIdentity = IDENTITY,
                onEvent = events::add,
            ).run(RebaselineReason.FirstStart)

            state.current.view().keys shouldBe setOf(a, b)
            state.current.edgeView() shouldBe setOf(MirrorEdge(a, b, "blocks"))

            val head = checkpoint.read()
            head shouldBe feed.history().last()
            feed.readFrom(head) shouldBe emptyList()
            (events.single() as MirrorEvent.Rebaselined).issueCount shouldBe 2
        }
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    /** A [Rebaseline] over hand-built rows and a fake `dolt_log` of [history]. */
    private fun rebaseline(
        state: MirrorState,
        rows: List<ExportRow>,
        history: List<String> = listOf("c0", "c1", "head"),
    ) = Rebaseline(
        export = { rows },
        feed = DoltCommitFeed(fakeLog(history)),
        checkpoint = checkpoint,
        state = state,
        workspaceIdentity = IDENTITY,
        onEvent = events::add,
    )

    /** A [DiffQuery] answering only the `dolt_log` query, newest-first as Dolt prints it. */
    private fun fakeLog(history: List<String>) = DiffQuery { sql ->
        if (!sql.contains("dolt_log")) emptyList()
        else history.asReversed().map { mapOf<String, JsonElement>("commit_hash" to JsonPrimitive(it)) }
    }

    /** One `bd export` row: [fields] plus, when [dependsOn] is set, one `blocks` dependency. */
    private fun row(id: String, vararg fields: Pair<String, String>, dependsOn: String? = null): ExportRow {
        val dependencies = dependsOn?.let {
            ""","dependencies":[{"issue_id":"$id","depends_on_id":"$it","type":"blocks"}]"""
        } ?: ""
        val body = fields.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
        val json = Json.parseToJsonElement("""{"id":"$id",$body$dependencies}""") as JsonObject
        return ExportRow(id, json)
    }

    private companion object {
        /** Fixed so a rebuilt projector's dots are the ones [BaselineBuilder] would mint for this workspace. */
        const val IDENTITY = "rebaseline_scratch"

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
        } catch (e: Exception) {
            false
        }
    }
}
