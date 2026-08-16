package civictech.demo.beadsmirror.e2e

import civictech.cell.Timestamp
import civictech.cell.data.delta.TaggedMapDelta
import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.baseline.ExportRow
import civictech.demo.beadsmirror.equality.Divergence
import civictech.demo.beadsmirror.equality.FieldMismatch
import civictech.demo.beadsmirror.equality.MirrorExportEquality
import civictech.demo.beadsmirror.equality.UnexpectedIssue
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.feed.FeedCheckpoint
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.DoltFeedPoller
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorKey
import civictech.demo.beadsmirror.projector.MirrorProjector
import civictech.demo.beadsmirror.projector.SeededDefects
import civictech.demo.beadsmirror.sanitizedDoltDatabaseName
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/**
 * computenet-dqj.5.3 — the epic's **divergence controls**: proof that the
 * mirror's two structural guards are load-bearing, by seeding each one away
 * ([SeededDefects]) and watching computenet-dqj.5.1's equality comparator go
 * red on a real `bd` workspace — then clean again on the identical run with
 * the defect off.
 *
 * A test that only ever runs the *correct* pipeline cannot distinguish a guard
 * that works from a guard that is never reached. These four cases are what
 * make [ScriptedSequenceTest]'s green non-vacuous.
 *
 * **The pipeline is assembled here rather than through
 * [civictech.demo.beadsmirror.BeadsMirrorApp]** ([Pipeline] below): the seeded
 * defect is a test-only switch and deliberately does not exist on
 * [civictech.demo.beadsmirror.BeadsMirrorConfig], so a defective projector
 * cannot be reached through `start`. Everything the app wires is wired here
 * from the same public parts — [DoltCommitFeed], [FeedCheckpoint],
 * [DoltFeedPoller], [MirrorProjector] — minus the HTTP shell and the start-time
 * re-baseline, neither of which either control is about.
 *
 * **Scratch workspaces only** (epic computenet-dqj §4): every workspace comes
 * from [BdScratchWorkspace.create] (`bd --sandbox init`), and nothing here
 * points at this repository's live `.beads`.
 *
 * Guarded on `bd`/`dolt` being on PATH, like every other live-workspace test
 * in this module.
 */
class DivergenceControlTest {

    private lateinit var workspace: BdScratchWorkspace
    private val pipelines = mutableListOf<Pipeline>()
    private val tempDirs = mutableListOf<Path>()

    @BeforeEach
    fun setUp() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
        workspace = BdScratchWorkspace.create()
    }

    @AfterEach
    fun tearDown() {
        pipelines.forEach { it.close() }
        if (::workspace.isInitialized) workspace.close()
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    // -----------------------------------------------------------------
    // control 1 — the presence key (feature rule 3, design example 3)
    // -----------------------------------------------------------------

    /**
     * **Why this control needs an injected foreign put, and cannot be a pure
     * `bd` sequence.** The presence key guards against a field put the
     * projector *never observed*: a removal is tag-precise
     * (`[24-TMAP-04]`) and tombstones exactly the dots in the projector's own
     * `mintedLive`, so such a put survives the removal, and membership gated on
     * [MirrorKey.PRESENT] alone is what stops it from resurrecting the issue as
     * a partial record.
     *
     * In BDS1's single-projector pipeline every put is self-minted, so `REMOVED`
     * covers `mintedLive` exactly and no straggler can arise from `bd` alone —
     * seeding [SeededDefects.dropPresenceKey] against a pure `bd` sequence would
     * therefore leave the comparator green and prove nothing. The straggler that
     * does exist in this design arrives through the **replicated-delta seam**
     * (`OrMapCell.deltaInlet`, dots travel verbatim, `[24-TAG-01]`) — the exact
     * seam BDS2's replicated deltas will use, and which the epic already
     * anticipates as "synthetically stamped rows". [injectForeignStraggler] is
     * that one row: a put on a field of C, stamped with a **foreign** source
     * id, injected after C is folded and before `bd delete C`.
     *
     * With the guard intact: C's presence key is tombstoned by the removal, so
     * C is absent from `view()` even though the straggler key is still live in
     * the cell (asserted directly below, so this case cannot pass by the
     * straggler simply not being there) — and the comparator is clean.
     */
    @Test
    fun `with the presence key intact, a foreign straggler on a deleted issue stays out of the view`() {
        val run = presenceControl(SeededDefects.NONE)
        val idC = checkNotNull(run.idC)

        // The straggler really is live in the map — the removal could not
        // tombstone a dot this projector never minted.
        run.projector.rawValue(MirrorKey(idC, STRAGGLER_FIELD)).shouldNotBeNull() shouldBe STRAGGLER_VALUE

        // ...and it is the presence gate, not its absence, that keeps C out.
        run.divergences shouldBe emptyList()
        run.view.keys shouldBe setOf(run.idA, run.idB)
    }

    /**
     * The control: the identical run with [SeededDefects.dropPresenceKey]
     * seeded — nothing minted at slot 0, membership derived from any live field
     * key — and the equality check goes red in exactly the shape feature rule 3
     * demands: the deleted issue C **present as a partial record**, naming
     * exactly the straggler field that survived the removal.
     */
    @Test
    fun `divergence control - dropping the presence key resurrects the deleted issue as a partial record`() {
        val run = presenceControl(SeededDefects(dropPresenceKey = true))
        val idC = checkNotNull(run.idC)

        run.divergences shouldBe listOf(UnexpectedIssue(idC, setOf(STRAGGLER_FIELD)))
        run.view.keys shouldBe setOf(run.idA, run.idB, idC)
        run.view.getValue(idC) shouldBe mapOf(STRAGGLER_FIELD to STRAGGLER_VALUE)
    }

    // -----------------------------------------------------------------
    // control 2 — per-field keying (feature rule 4)
    // -----------------------------------------------------------------

    /**
     * The reference half: two single-field edits to one issue in **two separate
     * commits** both survive under the shipped composite (issue, field) keying,
     * and the fold equals `bd export`.
     *
     * The split pair is deliberate and is why this control does not reuse
     * [MutationScript]'s step 3, which writes `--status` and `--design` in ONE
     * commit: within a single record both puts are minted at or above the same
     * floor, so neither tombstones the other and even whole-issue keying would
     * lose only the delta-internal ordering. Two commits are what put the second
     * put's floor-bounded tombstone over the first put's dot.
     */
    @Test
    fun `with per-field keying, two single-field edits in separate commits both survive`() {
        val run = keyingControl(SeededDefects.NONE)

        run.divergences shouldBe emptyList()
        run.view.getValue(run.idA)["status"] shouldBe json("in_progress")
        run.view.getValue(run.idA)["design"] shouldBe json(MutationScript.DESIGN)
    }

    /**
     * The control: with [SeededDefects.wholeIssueKeying] seeded, every column of
     * an issue lands on one key, so the second edit's floor-bounded tombstone
     * buries the first — and the equality check goes red.
     *
     * **What is asserted, precisely.** Whole-issue keying collapses the *create*
     * too, so the comparator's report is broader than "one field missing": every
     * export field of A mismatches, because the fold no longer carries any field
     * under its own name. The two assertions below therefore split the claim:
     *
     * - the comparator names **both** split edits as absent from the fold
     *   ([FieldMismatch] with a `null` fold rendering) — the failure feature
     *   rule 4 asks for;
     * - and A's whole fold entry is the one collapsed key holding one value, so
     *   neither edit is merely stored under another name: both are gone from
     *   the map, exactly one put survives, and nothing can recover the rest.
     *
     * **Which put survives is not asserted here, and that is deliberate — it is
     * not deterministic.** A real `bd update --design` commit usually writes
     * `updated_at` in the same diff row, so that record collapses two columns
     * onto the key and the sorted-last one (`updated_at`) wins the delta before
     * any cross-commit tombstone is in play; but `updated_at` has
     * second granularity, so when the two `bd` invocations land inside the same
     * second the column does not change, the diff row omits it, and `design`
     * wins instead. Both outcomes were observed on 2026-08-16, minutes apart, on
     * this very test — asserting either one would have been a flake. What is
     * stable, and is what rule 4 is about, is that a single key survives and
     * neither edit is addressable any more.
     *
     * The tombstone mechanism itself — the *later commit's* floor-bounded del
     * burying the *earlier commit's* live dot — is pinned where a record can be
     * built with one column and no bookkeeping rider:
     * [civictech.demo.beadsmirror.projector.MirrorProjectorTest.Defects]'s
     * `wholeIssueKeying collapses every column onto one key and the later
     * commit buries the earlier`.
     */
    @Test
    fun `divergence control - whole-issue keying loses one of two different-field edits`() {
        val run = keyingControl(SeededDefects(wholeIssueKeying = true))

        run.divergences shouldContain FieldMismatch(run.idA, "status", null, run.exportRendering("status"))
        run.divergences shouldContain FieldMismatch(run.idA, "design", null, run.exportRendering("design"))

        // One key, one surviving put — every other put on this issue is
        // unrecoverable rather than mis-keyed. Under the shipped keying the same
        // sequence leaves `status` and `design` addressable side by side (the
        // reference case above).
        val fold = run.view.getValue(run.idA)
        fold.keys shouldBe setOf(SeededDefects.WHOLE_ISSUE_FIELD)
        run.projector.rawValue(MirrorKey(run.idA, SeededDefects.WHOLE_ISSUE_FIELD)).shouldNotBeNull()
    }

    // -----------------------------------------------------------------
    // the two scripted runs
    // -----------------------------------------------------------------

    /** What one control run leaves behind for its assertions. */
    private inner class Run(
        val projector: MirrorProjector,
        val divergences: List<Divergence>,
        val export: List<ExportRow>,
        val idA: String,
        /** The second issue of the presence sequence; `null` for the keying control, which creates only A. */
        val idB: String? = null,
        /** The created-then-deleted issue of the presence sequence; `null` for the keying control. */
        val idC: String? = null,
    ) {
        val view: Map<String, Map<String, String>> = projector.view()

        /** `bd export`'s own rendering of one of A's fields, quoted verbatim by a [FieldMismatch]. */
        fun exportRendering(field: String): String =
            export.single { it.id == idA }.json.getValue(field).toString()
    }

    /**
     * Steps 1-4 of [MutationScript] (create A, create B, `dep add`, the
     * multi-field update of A, `close B`), then the straggler case: create C,
     * fold it, inject one foreign-sourced put on C, `bd delete C --force`, fold
     * again.
     *
     * [MutationScript.afterRestart] is not used verbatim because it runs
     * `create C` and `delete C` back to back with no seam between them, and the
     * injection has to land in exactly that gap — C must be folded (so the
     * removal has something of its own to tombstone) before the put the
     * projector never observed arrives.
     */
    private fun presenceControl(defects: SeededDefects): Run {
        val pipeline = pipeline(defects)
        val script = MutationScript(workspace)

        script.beforeRestart()
        pipeline.drain()

        val idC = workspace.createIssue("Issue C")
        pipeline.drain()

        injectForeignStraggler(pipeline.projector, idC)

        workspace.run("delete", idC, "--force")
        pipeline.drain()

        val export = exportNow()
        return Run(
            projector = pipeline.projector,
            divergences = MirrorExportEquality.compare(
                pipeline.projector.view(),
                pipeline.projector.edgeView(),
                export,
            ),
            export = export,
            idA = script.idA,
            idB = script.idB,
            idC = idC,
        )
    }

    /** `create A`, then `--status` and `--design` as two separate one-field commits. */
    private fun keyingControl(defects: SeededDefects): Run {
        val pipeline = pipeline(defects)

        val idA = workspace.createIssue("Issue A")
        workspace.run("update", idA, "--status", "in_progress")
        workspace.run("update", idA, "--design", MutationScript.DESIGN)
        pipeline.drain()

        val export = exportNow()
        return Run(
            projector = pipeline.projector,
            divergences = MirrorExportEquality.compare(
                pipeline.projector.view(),
                pipeline.projector.edgeView(),
                export,
            ),
            export = export,
            idA = idA,
        )
    }

    /**
     * One put on `<issueId>/`[STRAGGLER_FIELD], stamped with a source id that is
     * **not** this projector's ([DotMinter.sourceId]) and injected straight
     * through the map's replicated-delta seam — a put the projector never
     * observed and therefore never recorded as live, which is precisely what a
     * tag-precise removal cannot tombstone.
     *
     * The counter is placed above every dot this workspace's history can mint
     * ([FOREIGN_HEIGHT_OFFSET] commits past the current head height), so the
     * straggler reads as a *later* foreign write rather than as a stale one.
     * Survival does not actually depend on that — the removal covers
     * `mintedLive`, which this dot is not in, at any counter — but a later dot
     * also wins last-writer-wins on the key, which keeps the assertion about the
     * presence gate rather than about dot ordering.
     */
    private fun injectForeignStraggler(projector: MirrorProjector, issueId: String) {
        val headHeight = DoltCommitFeed(workspace.doltRoot).history().size.toLong()
        val dot = Timestamp(
            FOREIGN_SOURCE,
            DotMinter.counter(FeedPosition(headHeight + FOREIGN_HEIGHT_OFFSET, 0), 0),
        )
        projector.cell.deltaInlet.call.propagate(
            TaggedMapDelta(puts = mapOf(MirrorKey(issueId, STRAGGLER_FIELD) to mapOf(dot to STRAGGLER_VALUE))),
        )
    }

    // -----------------------------------------------------------------
    // fixture
    // -----------------------------------------------------------------

    /**
     * The mirror pipeline minus the app: feed -> checkpointed poller ->
     * projector, driven one synchronous tick at a time.
     *
     * [DoltFeedPoller.pollOnce] rather than [DoltFeedPoller.start]: one tick
     * reads *everything* after the checkpoint, so a control's fold is a
     * deterministic function of the `bd` commands run before the tick — no
     * background thread, no bounded wait, and no way for an injection to race a
     * poll it must sit between.
     */
    private inner class Pipeline(defects: SeededDefects, runDir: Path) : AutoCloseable {

        val projector = MirrorProjector(DotMinter(sanitizedDoltDatabaseName(workspace.root)), defects)

        private val feed = DoltCommitFeed(workspace.doltRoot)
        private val checkpoint = FeedCheckpoint(runDir)
        private val poller = DoltFeedPoller(
            feed = feed,
            checkpoint = checkpoint,
            interval = Duration.ZERO,
            onBatch = { records -> projector.applyAll(records) },
        )

        /** One tick, then the proof it consumed everything: nothing is left after the checkpoint. */
        fun drain() {
            poller.pollOnce()
            feed.readFrom(checkpoint.read()) shouldBe emptyList()
        }

        override fun close() = poller.close()
    }

    private fun pipeline(defects: SeededDefects): Pipeline =
        Pipeline(defects, tempDir("beadsmirror-divergence-run-")).also { pipelines.add(it) }

    private fun exportNow(): List<ExportRow> = BdExportReader(workspace.root).read()

    private fun json(value: String): String = JsonPrimitive(value).toString()

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

    private companion object {

        /** The field the injected straggler writes — a real `issues` column, so it is a rendering `bd export` could have printed. */
        const val STRAGGLER_FIELD: String = "design"

        /** Its value, in the JSON string form the projector stores field values under. */
        val STRAGGLER_VALUE: String = JsonPrimitive("a put this mirror never observed").toString()

        /**
         * The straggler's dot source: **not** [DotMinter.sourceId] for this
         * workspace, which is derived from `beads-mirror-dots:<identity>`.
         */
        val FOREIGN_SOURCE: UUID = UUID.nameUUIDFromBytes("beads-mirror-foreign-replica".toByteArray())

        /** How far past the current head height the foreign dot is stamped. */
        const val FOREIGN_HEIGHT_OFFSET: Long = 1000
    }
}
