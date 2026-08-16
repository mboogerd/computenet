package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.BeadsMirrorApp
import civictech.demo.beadsmirror.BeadsMirrorConfig
import civictech.demo.beadsmirror.baseline.BaselineBuilder
import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.baseline.ExportRow
import civictech.demo.beadsmirror.baseline.MirrorEvent
import civictech.demo.beadsmirror.baseline.RebaselineReason
import civictech.demo.beadsmirror.equality.MirrorExportEquality
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.projector.MirrorEdge
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Collections

/**
 * computenet-dqj.5.2 — the epic's end-to-end equality gate: a real scripted
 * `bd` sequence ([MutationScript]) against a real throwaway workspace, mirrored
 * by a real [BeadsMirrorApp], compared field-for-field against that same
 * workspace's `bd export` (computenet-dqj.5.1's [MirrorExportEquality]).
 *
 * Three cases, one per rule of the feature's acceptance:
 * - the uninterrupted sequence (rule 1) — in the projector's own state *and*
 *   over HTTP, so "served" is literally what is checked;
 * - a stop/restart mid-sequence on the same workspace and run dir (rule 2);
 * - a `bd flatten` mid-run and one further mutation (the feature's compaction
 *   case; the unit coverage of the rebuild itself lives in computenet-dqj.3).
 *
 * **Scratch workspaces only** (rule 5, epic computenet-dqj §4). Every workspace
 * here comes from [BdScratchWorkspace.create] (`bd --sandbox init` into a fresh
 * temp directory), and `repoSearchRoot` points at a throwaway tree so the
 * app's own live-`.beads` refusal has nothing of this repository to find. That
 * refusal itself is not re-tested here — it is
 * [civictech.demo.beadsmirror.BeadsMirrorAppTest]'s `Refusal` nest, including
 * the case that proves `start` refuses *before* spawning a `bd export`.
 *
 * **Order: the app starts first, then the script runs.** Every issue therefore
 * reaches the fold through the `dolt_diff_issues` feed rather than through the
 * start-time baseline — which is the pipeline this feature exists to check.
 * (A start against a not-yet-populated workspace is fine: a fresh
 * `bd --sandbox init` already has 8 `dolt_log` commits and an empty export,
 * so the baseline captures a head and 0 issues.)
 *
 * Guarded on `bd`/`dolt` being on PATH, like every other live-workspace test
 * in this module: CI installs neither and runs this green-but-skipped.
 */
class ScriptedSequenceTest {

    private lateinit var workspace: BdScratchWorkspace
    private lateinit var isolatedSearchRoot: Path
    private val mirrors = mutableListOf<Mirror>()
    private val tempDirs = mutableListOf<Path>()

    @BeforeEach
    fun setUp() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
        workspace = BdScratchWorkspace.create()
        isolatedSearchRoot = tempDir("beadsmirror-e2e-searchroot-")
    }

    @AfterEach
    fun tearDown() {
        mirrors.forEach { it.close() }
        if (::workspace.isInitialized) workspace.close()
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    /**
     * Feature rule 1 and design example 1: after the whole scripted sequence
     * quiesces, the fold equals `bd export` on every compared field, carries
     * exactly the non-removed issues (C, `bd delete`d, is absent), and holds
     * exactly the edge `B -> A`.
     *
     * Asserted twice over the same quiesced mirror: once on
     * [civictech.demo.beadsmirror.projector.MirrorProjector.view]/`edgeView`
     * directly, and once on the fold **as served** by
     * [civictech.demo.beadsmirror.http.MirrorRoutes] — reconstructed from
     * `GET /beads/issues` plus each issue's `dependencies` array. The rule
     * says "the served map", and a fold that were right in memory and wrong on
     * the wire would satisfy only the first of these.
     */
    @Test
    fun `the scripted sequence quiesces to a fold equal to bd export, in state and as served`() {
        val mirror = startMirror(runDir = tempDir("beadsmirror-e2e-run-"))
        val script = MutationScript(workspace)

        script.runAll()
        mirror.quiesce()

        val export = exportNow()
        val state = mirror.stateFold()

        MirrorExportEquality.compare(state.view, state.edges, export) shouldBe emptyList()
        state.view.keys shouldBe setOf(script.idA, script.idB)
        state.edges shouldBe script.expectedEdges

        val served = mirror.servedFold()
        MirrorExportEquality.compare(served.view, served.edges, export) shouldBe emptyList()
        served.view.keys shouldBe setOf(script.idA, script.idB)
        served.edges shouldBe script.expectedEdges
    }

    /**
     * Feature rule 2 and design example 2: the mirror is stopped after
     * `close B`, the rest of the sequence runs while it is down, and a second
     * [BeadsMirrorApp.start] against the **same workspace and run dir**
     * converges on the same answer as a mirror that never stopped.
     *
     * This is the case the start-time rebuild (design amendment 2,
     * `BeadsMirrorApp.start`) exists for. Before it, `start` baselined only
     * when no checkpoint existed, so this second start would have resumed the
     * feed strictly after the persisted checkpoint into a *fresh empty*
     * projector: A and B — created before the stop — would never appear, and
     * the comparator reports them as missing. Verified by mutation on
     * 2026-08-16: restoring `if (checkpoint.read() == null)` fails this test
     * with `[MissingIssue(A), MissingIssue(B), MissingEdge(B->A:blocks)]` —
     * which is also why the comparator assertions come before the
     * restart-reason one below, so the mutation reports the divergences rather
     * than only "the rebuild was a FirstStart".
     *
     * **What "equal to the uninterrupted run" can mean here, precisely.** The
     * `uninterrupted` mirror runs against the *same* workspace throughout, so
     * both folds carry the same issue ids and the same timestamps and are
     * genuinely comparable — but not byte-for-byte: a feed-built fold carries
     * every `issues` column (an ADDED diff row has no `from_` side, so every
     * column shows as a change), while an export-baselined one carries only
     * the keys `bd export` printed. That difference is exactly
     * [MirrorExportEquality.FEED_ONLY], and it is *not* a divergence — so the
     * direct fold-to-fold assertion below is stated on the compared universe
     * (export's keys minus exclusions), plus an exact equality of the edge
     * sets, where no such asymmetry exists.
     */
    @Test
    fun `a mirror stopped mid-sequence and restarted converges on the uninterrupted run's fold`() {
        val uninterrupted = startMirror(runDir = tempDir("beadsmirror-e2e-run-uninterrupted-"))
        val restarted = startMirror(runDir = tempDir("beadsmirror-e2e-run-restarted-"))
        val script = MutationScript(workspace)

        script.beforeRestart()
        restarted.quiesce()
        restarted.stop()

        script.afterRestart()
        restarted.start()

        restarted.quiesce()
        uninterrupted.quiesce()

        val export = exportNow()
        val restartedFold = restarted.stateFold()
        val uninterruptedFold = uninterrupted.stateFold()

        MirrorExportEquality.compare(restartedFold.view, restartedFold.edges, export) shouldBe emptyList()
        MirrorExportEquality.compare(uninterruptedFold.view, uninterruptedFold.edges, export) shouldBe emptyList()

        // The restart really was a restart: a Restart-reason rebuild off the
        // checkpoint the first run persisted, not a FirstStart one.
        val restartEvent = restarted.events.filterIsInstance<MirrorEvent.Rebaselined>().last()
        (restartEvent.reason is RebaselineReason.Restart) shouldBe true

        restartedFold.view.keys shouldBe setOf(script.idA, script.idB)
        restartedFold.edges shouldBe uninterruptedFold.edges
        restartedFold.edges shouldBe script.expectedEdges

        // The direct fold-to-fold comparison: the uninterrupted run's fold,
        // restricted to the compared universe, re-rendered as export rows and
        // fed to the same comparator — which is what supplies the datetime
        // normalization the two renderings need (measured: a raw string
        // comparison fails only on `"2026-08-16 05:05:38"` vs
        // `"2026-08-16T05:05:38Z"`, the very asymmetry computenet-dqj.5.1
        // pinned).
        MirrorExportEquality.compare(
            restartedFold.view,
            restartedFold.edges,
            uninterruptedFold.asComparableExportRows(export),
        ) shouldBe emptyList()
    }

    /**
     * The feature's compaction case: `bd flatten --force` squashes the
     * mirror's checkpoint out of `dolt_log` mid-run, the mirror re-baselines
     * ([MirrorEvent.Rebaselined] with [RebaselineReason.CheckpointGone], and
     * [civictech.demo.beadsmirror.MirrorState.rebaselineCount] advancing past
     * the start-time rebuild), and one further mutation afterwards leaves the
     * fold equal to `bd export` again.
     *
     * Cheap here because the whole pipeline is already standing; the unit
     * coverage of the rebuild mechanism itself is computenet-dqj.3's
     * (`BeadsMirrorAppTest`, "a flattened-away checkpoint rebuilds from
     * export, then resumes incrementally").
     */
    @Test
    fun `a flatten mid-run re-baselines and equality holds again`() {
        val mirror = startMirror(runDir = tempDir("beadsmirror-e2e-run-flatten-"))
        val script = MutationScript(workspace)

        script.runAll()
        mirror.quiesce()
        MirrorExportEquality.compare(
            mirror.stateFold().view,
            mirror.stateFold().edges,
            exportNow(),
        ) shouldBe emptyList()
        mirror.app.state.rebaselineCount shouldBe 1

        workspace.flatten()
        awaitUntil("the mirror re-baselines past the flattened-away checkpoint") {
            mirror.events.count { it is MirrorEvent.Rebaselined } == 2
        }

        // The further mutation waits for the rebuild rather than racing it.
        // `Rebaseline.run` reads `bd export` BEFORE capturing the head commit
        // (the concurrent-writer race its own KDoc accepts), so a mutation
        // landing between those two reads is checkpointed as consumed while
        // its content is absent from the snapshot — measured here on
        // 2026-08-16 as a stale `priority`/`updated_at` pair surviving a
        // quiesced fold. That is a real property of the production code, not
        // a test artifact, and it belongs to whoever tightens the baseline
        // snapshot, not to this equality gate.
        workspace.run("update", script.idA, "--priority", "0")
        mirror.quiesce()

        val rebuild = mirror.events.filterIsInstance<MirrorEvent.Rebaselined>().last()
        (rebuild.reason is RebaselineReason.CheckpointGone) shouldBe true
        mirror.app.state.rebaselineCount shouldBe 2

        val export = exportNow()
        val fold = mirror.stateFold()
        MirrorExportEquality.compare(fold.view, fold.edges, export) shouldBe emptyList()
        fold.view.keys shouldBe setOf(script.idA, script.idB)
        fold.edges shouldBe script.expectedEdges
    }

    // -----------------------------------------------------------------
    // fixture
    // -----------------------------------------------------------------

    /** A fold as this test compares it: the issue map and the edge set, from either side of the HTTP boundary. */
    private data class Fold(val view: Map<String, Map<String, String>>, val edges: Set<MirrorEdge>)

    /**
     * One [BeadsMirrorApp] over this test's workspace, restartable against the
     * same [runDir] (which is what makes the persisted checkpoint survive a
     * stop — the restart case's whole premise).
     */
    private inner class Mirror(private val runDir: Path, private val pollInterval: Duration) : AutoCloseable {

        /**
         * Every [MirrorEvent] this mirror has produced, across restarts.
         * Synchronized: a [RebaselineReason.CheckpointGone] rebuild reports
         * from the poller thread while the test reads from its own.
         */
        val events: MutableList<MirrorEvent> = Collections.synchronizedList(mutableListOf())

        private var running: BeadsMirrorApp? = null
        private var probe: HttpProbe? = null

        val app: BeadsMirrorApp get() = checkNotNull(running) { "mirror is stopped" }

        fun start() {
            check(running == null) { "already started" }
            val started = BeadsMirrorApp.start(
                BeadsMirrorConfig(
                    workspace = workspace.root,
                    pollInterval = pollInterval,
                    runDir = runDir,
                    repoSearchRoot = isolatedSearchRoot,
                    onEvent = events::add,
                ),
            )
            running = started
            probe = HttpProbe("http://localhost:${started.boundPort}")
        }

        fun stop() {
            probe?.close()
            probe = null
            running?.stop()
            running = null
        }

        override fun close() = stop()

        /**
         * Waits until this mirror has consumed the workspace's whole history:
         * its persisted checkpoint equals the head of `dolt_log`, which the
         * poller writes only *after* handing the batch to the projector — so
         * "checkpoint at head" means "every record applied". Then asserts the
         * poll loop is still alive, since a dead poller also stops advancing.
         *
         * Bounded via [awaitUntil], no sleeps (AGENTS.md's testkit rule).
         */
        fun quiesce() {
            val feed = DoltCommitFeed(workspace.doltRoot)
            awaitUntil("mirror at $runDir reaches the workspace's head commit") {
                app.pollerFailure == null && checkpoint() == feed.history().last()
            }
            app.pollerFailure shouldBe null
        }

        private fun checkpoint(): String? =
            runDir.resolve("checkpoint").takeIf { Files.exists(it) }?.let { Files.readString(it).trim() }

        /** The fold as the projector holds it. */
        fun stateFold(): Fold = Fold(app.state.current.view(), app.state.current.edgeView())

        /**
         * The fold as [civictech.demo.beadsmirror.http.MirrorRoutes] serves it:
         * `GET /beads/issues` for the issue map (each field value re-rendered
         * from the parsed JSON, which is the same `JsonElement.toString()` form
         * the projector stores), and one `GET /beads/issues/{id}` per issue for
         * that issue's owning-side dependency edges.
         */
        fun servedFold(): Fold {
            val http = checkNotNull(probe) { "mirror is stopped" }
            val listed = Json.parseToJsonElement(http.get("/beads/issues").body()).jsonObject
            val view = listed.mapValues { (_, fields) ->
                fields.jsonObject.mapValues { (_, value) -> value.toString() }
            }
            val edges = view.keys.flatMap { id ->
                val body = Json.parseToJsonElement(http.get("/beads/issues/$id").body()).jsonObject
                body.getValue("dependencies").jsonArray.map { edge ->
                    MirrorEdge(
                        issueId = edge.jsonObject.getValue("issue_id").jsonPrimitive.content,
                        dependsOnIssueId = edge.jsonObject.getValue("depends_on_issue_id").jsonPrimitive.content,
                        type = edge.jsonObject.getValue("type").jsonPrimitive.content,
                    )
                }
            }.toSet()
            return Fold(view, edges)
        }
    }

    private fun startMirror(runDir: Path, pollInterval: Duration = Duration.ofMillis(200)): Mirror =
        Mirror(runDir, pollInterval).also {
            mirrors.add(it)
            it.start()
        }

    private fun exportNow(): List<ExportRow> = BdExportReader(workspace.root).read()

    /**
     * This fold re-rendered as [ExportRow]s, so it can stand on the *export*
     * side of [MirrorExportEquality.compare] and one fold can be compared
     * directly against another (the restart rule's "equal to the uninterrupted
     * run's fold"). Two things make that comparison mean what it should:
     *
     * - **Fields are restricted to [reference]'s compared universe** (each real
     *   export row's keys minus [BaselineBuilder.EXCLUDED_FIELDS]). Without the
     *   restriction, a feed-built fold's [MirrorExportEquality.FEED_ONLY]
     *   columns would enter the universe and be *demanded* of an
     *   export-baselined fold that legitimately never carries them.
     * - **Edges are re-rendered into a `dependencies` array** using `bd
     *   export`'s own `depends_on_id` spelling, because that array is where
     *   [MirrorExportEquality.compare] reads the expected edge set from; a row
     *   without it would make every real edge an `UnexpectedEdge`.
     *
     * The comparator's normalization (datetime renderings above all) then
     * applies to the fold-vs-fold comparison exactly as it does to
     * fold-vs-export.
     */
    private fun Fold.asComparableExportRows(reference: List<ExportRow>): List<ExportRow> {
        val universes = reference.associate { it.id to (it.json.keys - BaselineBuilder.EXCLUDED_FIELDS) }
        return view.map { (id, fields) ->
            val universe = universes[id] ?: emptySet()
            val json = buildJsonObject {
                fields.filterKeys { it in universe }
                    .forEach { (field, rendering) -> put(field, Json.parseToJsonElement(rendering)) }
                put(
                    ExportRow.DEPENDENCIES_FIELD,
                    buildJsonArray {
                        edges.filter { it.issueId == id }.forEach { edge ->
                            add(
                                buildJsonObject {
                                    put("issue_id", JsonPrimitive(edge.issueId))
                                    put("depends_on_id", JsonPrimitive(edge.dependsOnIssueId))
                                    put("type", JsonPrimitive(edge.type))
                                },
                            )
                        }
                    },
                )
            }
            ExportRow(id, json)
        }
    }

    private fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix).also { tempDirs.add(it) }

    private fun commandAvailable(vararg command: String): Boolean = try {
        ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    } catch (e: Exception) {
        false
    }
}
