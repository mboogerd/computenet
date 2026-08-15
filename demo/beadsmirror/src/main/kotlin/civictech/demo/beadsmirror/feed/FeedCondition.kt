package civictech.demo.beadsmirror.feed

/**
 * A condition the feed cannot read past without outside help. Sealed so a
 * consumer's `when` stays exhaustive as more conditions are added — today
 * there is exactly one: history truncation.
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
        }
    }
}
