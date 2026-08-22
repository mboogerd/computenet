package civictech.demo.beadsmirror

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
import civictech.demo.beadsmirror.feed.FeedCheckpoint
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
     * computenet-dqj.10: a commit that lands *while the baseline is being
     * taken* must not be checkpointed as consumed.
     *
     * The export snapshot here is read before `concurrent` exists (the lambda
     * appends to the fake `dolt_log` on its way out, standing in for a writer
     * committing during the export subprocess), so nothing in the rebuilt
     * state carries that commit's content. Checkpointing it would tell the
     * poller to resume strictly after it, and its content would never be
     * folded — the fold would keep the pre-commit values forever. The
     * checkpoint must therefore stay at the head observed *before* the
     * snapshot, leaving `concurrent` for the feed.
     */
    @Test
    fun `a commit landing while the export is read is left for the feed, not checkpointed as consumed`() {
        val log = mutableListOf("c0", "c1", "head")
        val state = MirrorState(MirrorProjector(DotMinter(IDENTITY)))

        Rebaseline(
            export = {
                val rows = listOf(row("B", "title" to "Beta"))
                log += "concurrent" // the concurrent writer; its content is NOT in `rows`
                rows
            },
            feed = DoltCommitFeed(fakeLog(log)),
            checkpoint = checkpoint,
            state = state,
            workspaceIdentity = IDENTITY,
            onEvent = events::add,
        ).run(RebaselineReason.FirstStart)

        checkpoint.read() shouldBe "head"
        (events.single() as MirrorEvent.Rebaselined).headCommit shouldBe "head"
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
    // computenet-dqj.13 — a zero-row export must not silently replace a fold
    // -----------------------------------------------------------------

    /**
     * computenet-dqj.13: a `bd export` that SUCCEEDS and yields zero rows used
     * to swap an empty projector over a populated fold and checkpoint the
     * empty state as current — silently, because nothing about it is a
     * failure. Against the unfixed code this test failed with
     * "Expected a throwable, but nothing was thrown."
     *
     * The refusal must leave BOTH the fold and the checkpoint exactly as they
     * were, since the guard's whole value is that the previous state survives
     * to be served.
     */
    @Test
    fun `a zero-row export on a restart refuses instead of replacing the fold`() {
        val populated = MirrorProjector(DotMinter(IDENTITY))
        populated.apply(
            ChangeRecord(
                commitHash = "pre",
                position = FeedPosition(11, 0),
                issueId = "A",
                diffType = DiffType.ADDED,
                fieldDiffs = listOf(FieldDiff("title", old = null, new = JsonPrimitive("Alpha"))),
                edgeDiffs = emptyList(),
            ),
        )
        val state = MirrorState(populated)
        checkpoint.write("pre")

        val refusal = shouldThrow<EmptyExportRefused> {
            rebaseline(state, rows = emptyList()).run(RebaselineReason.Restart("pre"))
        }
        refusal.reason shouldBe RebaselineReason.Restart("pre")

        (state.current === populated) shouldBe true
        state.current.view().keys shouldBe setOf("A")
        checkpoint.read() shouldBe "pre"
        events shouldBe emptyList()
    }

    /**
     * The constraint that stops the guard being "reject zero rows": an empty
     * tracker is a legitimate state, and a first start against one must come
     * up. [EmptyExportRefused] documents why the reason is the only
     * discriminator available at start time.
     */
    @Test
    fun `a zero-row export on a first start is accepted, because an empty tracker is legitimate`() {
        val state = MirrorState(MirrorProjector(DotMinter(IDENTITY)))

        rebaseline(state, rows = emptyList()).run(RebaselineReason.FirstStart)

        state.current.view().keys shouldBe emptySet()
        checkpoint.read() shouldBe "head"
        (events.single() as MirrorEvent.Rebaselined).issueCount shouldBe 0
    }

    @Test
    fun `the explicit override accepts the empty export the guard would refuse`() {
        val populated = MirrorProjector(DotMinter(IDENTITY))
        populated.apply(
            ChangeRecord(
                commitHash = "pre",
                position = FeedPosition(11, 0),
                issueId = "A",
                diffType = DiffType.ADDED,
                fieldDiffs = listOf(FieldDiff("title", old = null, new = JsonPrimitive("Alpha"))),
                edgeDiffs = emptyList(),
            ),
        )
        val state = MirrorState(populated)
        checkpoint.write("pre")

        Rebaseline(
            export = { emptyList() },
            feed = DoltCommitFeed(fakeLog(listOf("c0", "c1", "head"))),
            checkpoint = checkpoint,
            state = state,
            workspaceIdentity = IDENTITY,
            onEvent = events::add,
            acceptEmptyExport = true,
        ).run(RebaselineReason.Restart("pre"))

        state.current.view().keys shouldBe emptySet()
        checkpoint.read() shouldBe "head"
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
            MirrorEvent.Rebaselined(RebaselineReason.CheckpointGone("gone-hash"), "head", 2, IDENTITY),
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

        /**
         * computenet-dqj.10 against a real workspace, with the interference
         * forced rather than raced: the `export` seam runs a real `bd export`
         * and *then* issues a real `bd update`, so the mutation's commit is
         * one the snapshot provably does not contain. It must be left after
         * the checkpoint, where the poller will fold it — this is the measured
         * sequence from computenet-dqj.5.2 (a mutation issued while a rebuild
         * was in flight left a stale priority in a quiesced fold).
         */
        @Test
        fun `a workspace mutation landing while the export is read is left for the feed to fold`() {
            val a = workspace.createIssue("Issue A")
            val feed = DoltCommitFeed(workspace.doltRoot)
            val reader = BdExportReader(workspace.root)
            val state = MirrorState(MirrorProjector(DotMinter(IDENTITY)))
            var concurrentCommit: String? = null

            Rebaseline(
                export = {
                    val rows = reader.read()
                    workspace.run("update", a, "--priority", "0")
                    concurrentCommit = feed.history().last()
                    rows
                },
                feed = feed,
                checkpoint = checkpoint,
                state = state,
                workspaceIdentity = IDENTITY,
                onEvent = events::add,
            ).run(RebaselineReason.FirstStart)

            val checkpointed = checkpoint.read()!!
            (checkpointed == concurrentCommit) shouldBe false
            val resumed = feed.readFrom(checkpointed)
            resumed.any { it.commitHash == concurrentCommit && it.issueId == a } shouldBe true
        }
        /**
         * computenet-dqj.13 with a REAL `bd --sandbox export` producing the
         * zero-row-but-successful output, rather than a stubbed `emptyList()`.
         *
         * The empty export comes from a second, freshly-initialised scratch
         * workspace — verified 2026-08-16 to exit 0 and emit zero rows — while
         * the checkpoint and the fold belong to the populated one. That
         * substitution stands in for whatever real path makes an export come
         * back empty; the epic reviewer's own trigger (removing
         * `.beads/config.yaml`) does not reproduce on this bd build, so the
         * shape is reproduced honestly instead of the trigger being faked.
         */
        @Test
        fun `a real zero-row bd export is refused on a restart, leaving the workspace's fold served`() {
            val a = workspace.createIssue("Issue A")
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

            val populated = state.current
            populated.view().keys shouldBe setOf(a)
            val head = checkpoint.read()

            BdScratchWorkspace.create().use { empty ->
                val emptyRows = BdExportReader(empty.root).read()
                emptyRows shouldBe emptyList() // a real bd export, exit 0, zero rows

                shouldThrow<EmptyExportRefused> {
                    Rebaseline(
                        export = BdExportReader(empty.root)::read,
                        feed = feed,
                        checkpoint = checkpoint,
                        state = state,
                        workspaceIdentity = IDENTITY,
                        onEvent = events::add,
                    ).run(RebaselineReason.Restart(head!!))
                }
            }

            (state.current === populated) shouldBe true
            state.current.view().keys shouldBe setOf(a)
            checkpoint.read() shouldBe head
        }

        /** The other side of the same rule, against a real empty workspace: a first start comes up. */
        @Test
        fun `a first start against a genuinely empty workspace comes up on the empty export`() {
            BdScratchWorkspace.create().use { empty ->
                val state = MirrorState(MirrorProjector(DotMinter(IDENTITY)))
                Rebaseline(
                    export = BdExportReader(empty.root)::read,
                    feed = DoltCommitFeed(empty.doltRoot),
                    checkpoint = checkpoint,
                    state = state,
                    workspaceIdentity = IDENTITY,
                    onEvent = events::add,
                ).run(RebaselineReason.FirstStart)

                state.current.view().keys shouldBe emptySet()
                checkpoint.read() shouldBe DoltCommitFeed(empty.doltRoot).history().last()
                (events.single() as MirrorEvent.Rebaselined).issueCount shouldBe 0
            }
        }
    }

    // -----------------------------------------------------------------
    // computenet-7em.4.1 — the post-pull merge condition
    // -----------------------------------------------------------------

    /**
     * The new reason is carried like any other: [Rebaseline.run] is
     * reason-agnostic, so this pins that the merge path really does rebuild
     * from the export and reports itself as a merge rather than as truncation
     * (which is what would let a `bd dolt pull` be misread as history
     * compaction in an operator's event log).
     */
    @Test
    fun `a re-baseline triggered by a merged history rebuilds from the export and names that reason`() {
        val stale = MirrorProjector(DotMinter(IDENTITY))
        stale.apply(
            ChangeRecord(
                commitHash = "pre-pull",
                position = FeedPosition(11, 0),
                issueId = "A",
                diffType = DiffType.ADDED,
                fieldDiffs = listOf(FieldDiff("title", old = null, new = JsonPrimitive("stale Alpha"))),
                edgeDiffs = emptyList(),
            ),
        )
        val state = MirrorState(stale)
        checkpoint.write("pre-pull")

        rebaseline(
            state,
            // The union the pull produced: the local issue with its current
            // title, plus the peer's.
            rows = listOf(row("A", "title" to "Alpha"), row("P", "title" to "Peer")),
            history = listOf("c0", "pre-pull", MERGE),
        ).run(RebaselineReason.HistoryMerged(MERGE))

        state.current.view().keys shouldBe setOf("A", "P")
        state.current.view().getValue("A")["title"] shouldBe "\"Alpha\""
        checkpoint.read() shouldBe MERGE
        events shouldBe listOf(MirrorEvent.Rebaselined(RebaselineReason.HistoryMerged(MERGE), MERGE, 2, IDENTITY))
    }

    /**
     * Feature rule 4's export-failure clause at this seam. Nothing is caught in
     * [Rebaseline.run], so a `bd export` that throws under the new reason must
     * propagate out of `pollOnce` — through the poller's loop, which records it
     * as [PollLoopStopped] (what `MirrorRoutes` serves `503` from via
     * `PollLoopDied`) — leaving the fold and the checkpoint exactly as they
     * were. The alternative is the one outcome the feature forbids: continuing
     * to serve a pre-pull fold as current.
     *
     * The poller here is wired exactly as `BeadsMirrorApp` wires it — the
     * condition maps to `rebaseline.run(HistoryMerged)` synchronously on the
     * poller thread.
     */
    @Test
    fun `a failing export under the merged-history reason freezes the mirror instead of serving pre-pull state`() {
        val prePull = MirrorProjector(DotMinter(IDENTITY))
        prePull.apply(
            ChangeRecord(
                commitHash = "pre-pull",
                position = FeedPosition(11, 0),
                issueId = "A",
                diffType = DiffType.ADDED,
                fieldDiffs = listOf(FieldDiff("title", old = null, new = JsonPrimitive("Alpha"))),
                edgeDiffs = emptyList(),
            ),
        )
        val state = MirrorState(prePull)
        checkpoint.write("pre-pull")

        val feed = DoltCommitFeed(mergedLog(listOf("c0", "pre-pull", MERGE)))
        val exportFailure = IllegalStateException("bd export exited 1")
        val rebaseline = Rebaseline(
            export = { throw exportFailure },
            feed = feed,
            checkpoint = checkpoint,
            state = state,
            workspaceIdentity = IDENTITY,
            onEvent = events::add,
        )
        val poller = DoltFeedPoller(
            feed = feed,
            checkpoint = checkpoint,
            interval = Duration.ofMillis(5),
            onBatch = { state.current.applyAll(it) },
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

            // The loop is dead and says so — the 503 path's whole input.
            val exit = poller.stopped
            exit.shouldNotBeNull()
            exit.failure shouldBe exportFailure
            exit.checkpoint shouldBe "pre-pull"
        }

        // ...and nothing moved: the pre-pull fold was neither replaced nor
        // advanced past, so no post-pull record was folded onto it either.
        (state.current === prePull) shouldBe true
        state.current.view().keys shouldBe setOf("A")
        checkpoint.read() shouldBe "pre-pull"
        events shouldBe emptyList()
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    /**
     * A fake `dolt_log` whose newest commit is a MERGE — the shape a real `bd
     * dolt pull` leaves (`parents` rendering read live 2026-08-17: two hashes
     * joined by comma-space). [history] is genesis-first.
     */
    private fun mergedLog(history: List<String>) = DiffQuery { sql ->
        if (!sql.contains("dolt_log")) {
            emptyList()
        } else {
            history.mapIndexed { index, hash ->
                val firstParent = if (index == 0) "" else history[index - 1]
                val parents = if (hash == MERGE) "$PEER_PARENT, $firstParent" else firstParent
                mapOf<String, JsonElement>(
                    "commit_hash" to JsonPrimitive(hash),
                    "parents" to JsonPrimitive(parents),
                )
            }.asReversed()
        }
    }

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

        /** The commit a `bd dolt pull` writes: two parents (computenet-7em.4.1). */
        const val MERGE = "merge-commit-hash"

        /** That merge's peer-side parent — the commit the pull brought in. */
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
        } catch (e: Exception) {
            false
        }
    }
}
