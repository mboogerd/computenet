package civictech.demo.beadsmirror.projector

import civictech.cell.Timestamp
import civictech.demo.beadsmirror.feed.FeedPosition
import java.util.UUID

/**
 * Mints the dots the mirror's deltas carry, **as a pure function of feed
 * position** (epic computenet-dqj §1).
 *
 * The mirror never lets a cell mint for it. `OrMapCell`'s own `MapOps` inlet
 * mints from an internal counter seeded at construction; that is replay-stable
 * only for a cell that replays its *own* journal, and the mirror's history
 * lives in Dolt, not in the cell. So dots are minted here and injected verbatim
 * through the `Replicable` delta seam (`[24-TAG-01]`: tags are data).
 *
 * Two properties are load-bearing, and both are properties of this class alone:
 *
 * - **Replay-identical.** `dot(position, keyIndex)` depends on nothing but its
 *   arguments and [workspaceIdentity], so re-reading a commit re-mints the same
 *   dot and the OR-map merge absorbs it as nothing new (`[24-TMAP-01]`
 *   idempotence). A random or counter-derived source would instead resurrect
 *   removed keys, because a pre-restart tombstone cannot cover a dot minted
 *   afresh after it.
 * - **Monotone in feed order.** [counter] packs `(commitHeight, ordinal,
 *   keyIndex)` into one Long with commit height most significant, so
 *   `TaggedMapDelta.DOT_ORDER` — `(counter, sourceId)`, no wall clock — orders
 *   dots exactly as the commit graph does. Last-writer-wins therefore agrees
 *   with commit order without any clock being read.
 *
 * The third component, `keyIndex`, is what keeps *distinct puts within one
 * record* on distinct dots. A shared per-record counter would break
 * `TaggedMapDelta`'s premise that a dot identifies exactly one put (its
 * idempotence argument rests on a dot always carrying the same value), so the
 * key's index inside the record's deterministically ordered key list rides in
 * the low bits.
 *
 * @param workspaceIdentity a stable identifier of the mirrored Dolt workspace —
 *   its database name or init-commit hash. **It must not change across
 *   restarts**: it is the dot source, and a changed source re-mints every dot
 *   under a name no existing tombstone covers.
 */
class DotMinter(val workspaceIdentity: String) {

    init {
        require(workspaceIdentity.isNotBlank()) {
            "DotMinter: workspaceIdentity must be a stable non-blank workspace identifier"
        }
    }

    /**
     * The dot source: a name-derived (v3) UUID over [workspaceIdentity], so two
     * processes mirroring the same workspace agree on it and a restart
     * reproduces it exactly.
     */
    val sourceId: UUID =
        UUID.nameUUIDFromBytes("beads-mirror-dots:$workspaceIdentity".toByteArray(Charsets.UTF_8))

    /** The dot for the [keyIndex]-th key of the record at [position]. */
    fun dot(position: FeedPosition, keyIndex: Int): Timestamp =
        Timestamp(sourceId, counter(position, keyIndex))

    companion object {
        /** Low bits: the key's index within its record's ordered key list. */
        const val KEY_INDEX_BITS: Int = 11

        /**
         * Middle bits: the record's ordinal within its commit.
         *
         * Widened 10 -> 20 by computenet-dqj.9. The ordinal is not only "issues
         * touched by one `bd` mutation" (which is 1 in the supported regime) —
         * [civictech.demo.beadsmirror.baseline.BaselineBuilder] mints the WHOLE
         * export at a single synthetic height, one ordinal per issue, so this
         * budget is the ceiling on **workspace size**, and the mirrored tracker
         * was 565 issues on 2026-08-16. 1023 was within 2x of ordinary growth;
         * 1_048_575 is not.
         */
        const val ORDINAL_BITS: Int = 20

        /**
         * High bits: the commit's height. 32 + 20 + 11 = 63 — sign bit unused.
         *
         * The 10 bits the ordinal gained came from here, because commit height
         * is the component with headroom to spare: one commit per `bd` mutation
         * (epic §4) means 2^32 heights is ~4.29e9 mutations — over ten thousand
         * years at a thousand mutations a day — while the ordinal budget was
         * being reached by a tracker that exists today.
         */
        const val COMMIT_HEIGHT_BITS: Int = 32

        const val MAX_KEY_INDEX: Int = (1 shl KEY_INDEX_BITS) - 1
        const val MAX_ORDINAL: Int = (1 shl ORDINAL_BITS) - 1
        const val MAX_COMMIT_HEIGHT: Long = (1L shl COMMIT_HEIGHT_BITS) - 1

        private const val ORDINAL_SHIFT: Int = KEY_INDEX_BITS
        private const val COMMIT_HEIGHT_SHIFT: Int = KEY_INDEX_BITS + ORDINAL_BITS

        /**
         * The packed dot counter. Each component is bounded loudly rather than
         * truncated: a silent overflow would alias two different puts onto one
         * dot, which the OR-map cannot detect and which would make the mirror
         * converge on a value no commit ever wrote.
         *
         * The budgets are generous for the regime this mirror supports (one
         * `bd` mutation per commit, epic §4): ~4.29e9 commits, 1_048_576 issues
         * per commit-or-baseline, 2048 keys touched per issue-record.
         *
         * The layout is not persisted anywhere and carries no compatibility
         * obligation: the mirror rebuilds its whole state from `bd export` on
         * every start (epic acceptance), so re-splitting the bits only changes
         * dots that are about to be re-minted from scratch. What it must not
         * change is the *shape* — commit height most significant, then ordinal,
         * then key index — because that is what makes the packed counter
         * monotone in feed order under `DOT_ORDER`.
         */
        fun counter(position: FeedPosition, keyIndex: Int): Long {
            require(position.commitHeight in 0..MAX_COMMIT_HEIGHT) {
                "DotMinter: commitHeight ${position.commitHeight} outside 0..$MAX_COMMIT_HEIGHT " +
                    "($COMMIT_HEIGHT_BITS bits)"
            }
            require(position.ordinal in 0..MAX_ORDINAL) {
                "DotMinter: ordinal ${position.ordinal} outside 0..$MAX_ORDINAL ($ORDINAL_BITS bits)"
            }
            require(keyIndex in 0..MAX_KEY_INDEX) {
                "DotMinter: keyIndex $keyIndex outside 0..$MAX_KEY_INDEX ($KEY_INDEX_BITS bits)"
            }
            return (position.commitHeight shl COMMIT_HEIGHT_SHIFT) or
                (position.ordinal.toLong() shl ORDINAL_SHIFT) or
                keyIndex.toLong()
        }
    }
}
