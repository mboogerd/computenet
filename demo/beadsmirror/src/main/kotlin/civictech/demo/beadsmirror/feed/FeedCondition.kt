package civictech.demo.beadsmirror.feed

/**
 * A condition the feed cannot read past without outside help. Sealed so a
 * consumer's `when` stays exhaustive as more conditions are added — today
 * there are two: history truncation and a merged history.
 */
sealed interface FeedCondition {

    /**
     * The persisted checkpoint commit [checkpoint] is no longer present in
     * `dolt_log` — history compaction/truncation (`bd compact`/`gc`, or
     * retention). The feed emits nothing past this point and does NOT fall
     * back to genesis or skip ahead; re-baselining from a bd export is
     * computenet-dqj.3's job, not this reader's.
     */
    data class CheckpointGone(val checkpoint: String) : FeedCondition

    /**
     * [mergeCommit] — a commit strictly after the feed's checkpoint (or
     * anywhere in history for a genesis read) — has two or more parents: a
     * real `bd dolt pull` has merged a peer's history in, and the linear,
     * gapless history the incremental walk assumes no longer holds
     * (feature computenet-7em.4, epic computenet-7em §2 bullet 4).
     *
     * Like [CheckpointGone] the feed emits nothing for that pass and neither
     * skips ahead nor walks the merged range; unlike it, the checkpoint is
     * still perfectly present in `dolt_log` (measured — see
     * [HistoryMergedException]), so this had to be looked for rather than
     * arriving for free. The answer is the same: re-baseline from `bd export`.
     */
    data class HistoryMerged(val mergeCommit: String) : FeedCondition
}

/**
 * Raised by [DoltFeedPoller] when it encounters a [FeedCondition] — the
 * exception-based half of "typed condition, surfaced as exception vs.
 * callback" (computenet-dqj.1.3): a condition is delivered to
 * [DoltFeedPoller]'s `onCondition` callback, whose default implementation
 * throws this, carrying [condition] so a catcher can pattern-match on it
 * rather than parse a message.
 */
class FeedConditionException(val condition: FeedCondition) : RuntimeException(describe(condition)) {
    private companion object {
        fun describe(condition: FeedCondition): String = when (condition) {
            is FeedCondition.CheckpointGone ->
                "checkpoint commit ${condition.checkpoint} is not in dolt_log (history truncated) " +
                    "— refusing to skip ahead or fall back to genesis"

            is FeedCondition.HistoryMerged ->
                "commit ${condition.mergeCommit} has 2+ parents (a pull merged peer history in) " +
                    "— refusing to walk merged history incrementally"
        }
    }
}
