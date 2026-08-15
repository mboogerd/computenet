package civictech.demo.beadsmirror.feed

/**
 * Where a [ChangeRecord] sits in the feed, derived from the Dolt commit graph
 * alone so that replaying the same commits yields identical positions —
 * downstream dot-minting (epic computenet-dqj §1) depends on that determinism.
 *
 * [commitHeight] is the commit's zero-based index in linear, genesis-first
 * history (the reverse of `dolt log`, which prints newest-first). History is
 * linear because bd runs under the default `--dolt-auto-commit` policy, one
 * commit per mutation — the only regime this feed supports (epic §4).
 *
 * [ordinal] orders the several issues a single commit may touch: records
 * within a commit are sorted by issue id, so the ordinal is a pure function of
 * the commit's contents and not of query or map iteration order.
 */
data class FeedPosition(val commitHeight: Long, val ordinal: Int) : Comparable<FeedPosition> {
    override fun compareTo(other: FeedPosition): Int =
        compareValuesBy(this, other, FeedPosition::commitHeight, FeedPosition::ordinal)

    override fun toString(): String = "$commitHeight:$ordinal"
}
