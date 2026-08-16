package civictech.demo.beadsmirror.baseline

import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.feed.EdgeDiff
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.FieldDiff
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorProjector
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns a `bd export` snapshot into projector state (computenet-dqj.3.1) —
 * the *baseline* half of re-baselining (feature computenet-dqj.3).
 *
 * **Export goes through the projector, not around it.** Each export row is
 * synthesised into one [DiffType.ADDED] [ChangeRecord] and fed through a
 * FRESH [MirrorProjector] by its ordinary [MirrorProjector.apply] path. That
 * reuse is the whole design: the presence key at slot 0, one `TaggedMapDelta`
 * per issue, the dependency `SetCell` adds, the cn_dot echo-drop registry
 * rebuild and every dot-packing rule fall out unchanged, so a baselined mirror
 * and a feed-replayed one are the same object built by the same code. A
 * separate "load state directly" path would be a second implementation of the
 * projection rules, drifting silently.
 *
 * **Baseline records mint AT the head height, and nothing else ever does.**
 * The head commit is *consumed* by the baseline — the checkpoint handed to the
 * orchestrator equals it, so the feed resumes strictly after it and no real
 * record is ever minted at that height. Every post-baseline commit therefore
 * has a strictly greater height, which is exactly what
 * [DotMinter]'s "monotone in feed order" property needs for last-writer-wins to
 * keep agreeing with commit order across a re-baseline.
 *
 * **Determinism.** The records are a pure function of (rows, head commit, head
 * height): rows are sorted by issue id and the ordinal is the index in that
 * order, mirroring [DoltCommitFeed]'s within-commit rule. With the same
 * [DotMinter.workspaceIdentity], building twice yields byte-identical dots
 * (`baseline-determinism` test), which is what lets a re-baseline be re-run
 * without resurrecting removed keys.
 *
 * **Bound (loud, not silent).** Ordinals are the record's slot in
 * [DotMinter]'s packing, capped at [DotMinter.MAX_ORDINAL] = 1023, so an
 * export of more than 1024 issues fails in `DotMinter.counter`'s `require`
 * with the ordinal named. That is a real ceiling on the workspaces this
 * baseline path can rebuild, accepted for this feature; widening it means
 * spreading the baseline over several synthetic heights, which the
 * mint-at-head-height rule above would have to be restated for.
 *
 * **Known asymmetry, deliberately not solved here.** Values are stored in
 * their export JSON form, verbatim, exactly as [MirrorProjector] stores the
 * feed's (`JsonElement.toString()`). `bd export` and `dolt_diff_issues` do not
 * always render the same field identically — e.g. `created_at` prints as
 * `"2026-08-16T00:42:53Z"` in the export while a diff row may render the same
 * datetime differently — so a baselined value and a feed-written value for the
 * same field can differ textually while meaning the same instant. Normalising
 * that is computenet-dqj.5's business (mirror-vs-export equality); this class
 * must not invent a normalisation of its own, because a normalisation applied
 * on only one of the two paths is worse than none.
 */
class BaselineBuilder(private val minter: DotMinter) {

    /**
     * The synthetic records for [rows], minted at [headHeight] under
     * [headCommit] — one [DiffType.ADDED] record per issue, ordinals assigned
     * by sorted issue id.
     */
    fun records(rows: List<ExportRow>, headCommit: String, headHeight: Long): List<ChangeRecord> {
        require(headCommit.isNotBlank()) { "BaselineBuilder: headCommit must be a non-blank commit hash" }
        require(headHeight >= 0) { "BaselineBuilder: headHeight $headHeight is negative" }
        return rows.sortedBy { it.id }.mapIndexed { ordinal, row ->
            ChangeRecord(
                commitHash = headCommit,
                position = FeedPosition(headHeight, ordinal),
                issueId = row.id,
                diffType = DiffType.ADDED,
                fieldDiffs = fieldDiffs(row),
                edgeDiffs = edgeDiffs(row),
                oldMetadata = null,
                newMetadata = row.metadata,
            )
        }
    }

    /**
     * Builds a fresh [MirrorProjector] holding exactly the state [rows]
     * describe. The projector is new on purpose: re-baselining replaces state
     * rather than merging into it, so the caller (the orchestration task) swaps
     * this in wholesale and no pre-gap issue can survive as a zombie.
     */
    fun build(rows: List<ExportRow>, headCommit: String, headHeight: Long): MirrorProjector =
        MirrorProjector(minter).also { it.applyAll(records(rows, headCommit, headHeight)) }

    /**
     * The row's issue fields as `null -> value` diffs, sorted by column.
     *
     * [EXCLUDED_FIELDS] are dropped: `_type` is bd's record discriminator,
     * `dependencies` is the nested edge array (translated by [edgeDiffs]
     * instead), and the three `*_count` fields are derived aggregates bd
     * computes at export time — none of them is a column of the `issues` table
     * the feed diffs, so mirroring them would make a baselined issue carry
     * keys a feed-built one never has (verified against a live export
     * 2026-08-16).
     *
     * A JSON-null value is treated as an absent field, matching
     * [DoltCommitFeed]'s own `valueOrNull` handling of SQL NULL, so the two
     * paths agree on which keys exist.
     */
    private fun fieldDiffs(row: ExportRow): List<FieldDiff> =
        row.json.keys.sorted()
            .filterNot { it in EXCLUDED_FIELDS }
            .mapNotNull { column ->
                val value = row.json[column]?.takeUnless { it is JsonNull } ?: return@mapNotNull null
                FieldDiff(column, old = null, new = value)
            }

    /**
     * The row's nested `dependencies` array as [EdgeDiff]s.
     *
     * NOTE the key names: the export spells the edge's far side
     * `depends_on_id`, while the `dependencies` *table* the feed diffs spells
     * it `depends_on_issue_id` (verified live 2026-08-16). Reading the table's
     * name here would silently yield zero edges on every baseline, so the two
     * spellings are pinned by a test.
     */
    private fun edgeDiffs(row: ExportRow): List<EdgeDiff> {
        val element = row.json[ExportRow.DEPENDENCIES_FIELD]?.takeUnless { it is JsonNull }
            ?: return emptyList()
        val array = element as? JsonArray
            ?: throw BdExportException(
                row.json.toString(),
                "\"${ExportRow.DEPENDENCIES_FIELD}\" of issue ${row.id} is not a JSON array",
            )
        return array.map { entry ->
            val dependency = entry as? JsonObject
                ?: throw BdExportException(
                    row.json.toString(),
                    "a \"${ExportRow.DEPENDENCIES_FIELD}\" entry of issue ${row.id} is not a JSON object",
                )
            EdgeDiff(
                diffType = DiffType.ADDED,
                issueId = dependency.requiredString(ISSUE_ID_FIELD, row),
                dependsOnIssueId = dependency.requiredString(DEPENDS_ON_ID_FIELD, row),
                type = dependency.requiredString(TYPE_FIELD, row),
            )
        }
    }

    private fun JsonObject.requiredString(key: String, row: ExportRow): String {
        val value = this[key]?.takeUnless { it is JsonNull }
            ?: throw BdExportException(
                row.json.toString(),
                "dependency of issue ${row.id} is missing \"$key\": $this",
            )
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw BdExportException(
                row.json.toString(),
                "dependency field \"$key\" of issue ${row.id} is not a string: $value",
            )
        if (text.isBlank()) {
            throw BdExportException(row.json.toString(), "dependency field \"$key\" of issue ${row.id} is blank")
        }
        return text
    }

    companion object {

        /**
         * Export keys that are not issue fields: the record discriminator, the
         * nested dependency array, and bd's derived counts.
         */
        val EXCLUDED_FIELDS: Set<String> = setOf(
            "_type",
            ExportRow.DEPENDENCIES_FIELD,
            "dependency_count",
            "dependent_count",
            "comment_count",
        )

        private const val ISSUE_ID_FIELD = "issue_id"
        private const val DEPENDS_ON_ID_FIELD = "depends_on_id"
        private const val TYPE_FIELD = "type"

        /**
         * The workspace's current head commit and its feed height, as
         * `(hash, height)`.
         *
         * [DoltCommitFeed.history] is genesis-first, so the head is its last
         * element and the height is `size - 1` — the same index the feed itself
         * would assign that commit, which is what makes the baseline's position
         * commensurable with the feed's.
         *
         * **Order of operations, and its one accepted race.** Call `bd export`
         * FIRST, then this, on a quiesced workspace. A writer committing
         * between the two would leave the baseline holding pre-commit content
         * checkpointed at a head that already includes it, so that commit would
         * never be replayed. The single-writer scratch workspaces this feature
         * targets do not have that writer, and closing the window properly
         * needs a snapshot read of both at one commit — out of scope by the
         * feature's own design decision, recorded here rather than only in the
         * tracker.
         *
         * @throws IllegalStateException if the workspace has no commits at all.
         */
        fun captureHead(feed: DoltCommitFeed): Pair<String, Long> {
            val history = feed.history()
            check(history.isNotEmpty()) { "BaselineBuilder: the workspace's dolt_log is empty — no head to capture" }
            return history.last() to (history.size - 1).toLong()
        }
    }
}
