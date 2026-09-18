package civictech.demo.beadsmirror.projector

import civictech.demo.beadsmirror.baseline.MirrorEvent
import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What an [EchoGate] decided about one [ChangeRecord] (feature computenet-6wc.3, decision 6wc.3-D4). */
enum class Classification {

    /**
     * This commit is the mirror's own write-back landing in `bd` — the write
     * half announced its token before importing, and this record carries that
     * token freshly written. It is not passed to the projector, so nothing is
     * minted and nothing is gossiped for it.
     */
    ECHO,

    /**
     * Everything else: a genuine local `bd` edit, an edit on a row the applier
     * stamped earlier (the stamp then sits unchanged on both sides), a removal,
     * an edge-only record, a record with no provenance at all. It reaches the
     * projector unchanged.
     */
    LOCAL,
}

/**
 * The announcement half of the echo seam: what the **writer** calls before and
 * after it imports a row, so the reader can recognise the commit that import
 * produces.
 *
 * Separated from [EchoGate] so the write-back applier
 * ([civictech.demo.beadsmirror.writeback.WriteBackApplier], task
 * computenet-6wc.3.2/.3) depends on the two methods it actually uses and not on
 * the classification surface it never calls. The gate owns this interface
 * because the gate is what has to honour it.
 */
interface EchoExpectations {

    /**
     * Announce that a commit writing `metadata.cn_echo = [token]` onto
     * [issueId] is about to be produced by this mirror's own applier. Called
     * immediately before `bd import`, on the applier's scheduler thread.
     */
    fun expectEcho(issueId: String, token: String)

    /**
     * Withdraw an expectation whose import did not land (a non-zero `bd import`
     * exit). Exact pair match: cancelling a token that was never registered, or
     * one already consumed by [EchoGate.classify], does nothing.
     */
    fun cancelEcho(issueId: String, token: String)
}

/**
 * Classifies every feed [ChangeRecord] as [Classification.ECHO] or
 * [Classification.LOCAL] between the poller and the projector, and passes only
 * the LOCAL ones on (feature computenet-6wc.3 clauses 2, 3 and 5; decisions
 * 6wc.3-D4, D6, D7).
 *
 * ## The rule, and why it is the two sides of the diff
 *
 * A record is ECHO iff **both**:
 *
 * 1. its `newMetadata.cn_echo` is a JSON string equal to a token this gate is
 *    holding for that record's `issueId` (the writer announced it through
 *    [expectEcho] before importing), and
 * 2. its `oldMetadata.cn_echo` is *not* that same string — i.e. **this commit
 *    wrote the token**, rather than merely finding it already on the row.
 *
 * Clause 2 is the whole reason this replaced the BDS1 `cn_dot` registry drop
 * (decision 6wc.3-D5). A stamp written by the applier **persists in bd's
 * `metadata`**, so every later genuine edit on that row carries the same stamp
 * on *both* diff sides; a rule keyed on "the mirror has seen this token/dot
 * before" therefore drops real edits on every stamped row, forever — feature
 * clause 3 violated permanently. Only the diff's two sides can say "this commit
 * wrote it", and that is the discriminator used here. Measured: the probe on
 * feature computenet-6wc.3's comment thread (2026-09-18) shows a plain
 * `bd update --priority` on a stamped row producing a `dolt_diff_issues` row
 * whose `from_metadata` and `to_metadata` both carry the stamp.
 *
 * `cn_dot` is **never** read for the decision. It is provenance (which replica
 * and dot the imposed value came from, decision 6wc.3-D1) and it can legitimately
 * repeat across two impositions of one row, so it cannot identify a commit. It
 * is carried into [MirrorEvent.RecordClassified] for diagnosis and nothing else.
 *
 * ## Lifetime of an expectation
 *
 * A match **consumes** the expectation, so one announced token suppresses at
 * most one commit. Unmatched expectations **never expire** (decision
 * 6wc.3-D7): a token is a fresh UUID written at most once, by exactly one
 * import, so a leftover one can never match a later record — it can only sit
 * in the set. Expiring them would need a clock and would buy nothing but the
 * risk of expiring a token whose commit is still in the poller's backlog. The
 * set is bounded in practice by the number of imports whose commit never
 * arrived (a crash between import and poll), which the next start's rebaseline
 * clears by constructing a new gate.
 *
 * ## Threading
 *
 * [expectEcho]/[cancelEcho] run on the applier's scheduler thread;
 * [classify]/[admit] run on the poll thread. The pending set is guarded by one
 * monitor, which is the whole synchronisation: the two counters are written
 * only by [admit] (poll thread, single writer) and are `@Volatile` for readers.
 *
 * ## Placement
 *
 * One gate per [civictech.demo.beadsmirror.WorkspaceMirror], **not** per
 * [MirrorProjector] (decision 6wc.3-D7): a re-baseline swaps the projector
 * wholesale, and an expectation registered before the swap must still suppress
 * the commit that arrives after it. Baseline records never pass through a gate
 * at all — [civictech.demo.beadsmirror.baseline.BaselineBuilder] feeds
 * `MirrorProjector.apply` directly — so a stamped export row projects its
 * `metadata` as an ordinary field, exactly like any other field.
 *
 * @param workspaceIdentity the attribution stamped onto every emitted
 *   [MirrorEvent]; the hosting mirror's own
 *   [civictech.demo.beadsmirror.WorkspaceMirror.identity].
 * @param onEvent the process-wide event sink. Defaults to a no-op so a test
 *   that only cares about the returned records need not wire one.
 */
class EchoGate(
    private val workspaceIdentity: String,
    private val onEvent: (MirrorEvent) -> Unit = {},
) : EchoExpectations {

    /** The announced-but-unseen `(issueId, token)` pairs. Guarded by its own monitor. */
    private val pending = LinkedHashSet<Pair<String, String>>()

    /**
     * How many records this gate has classified [Classification.ECHO] and
     * withheld from the projector. Written only by [admit] on the poll thread.
     */
    @Volatile
    var echoCount: Int = 0
        private set

    /** How many records this gate has passed through as [Classification.LOCAL]. Written only by [admit]. */
    @Volatile
    var localCount: Int = 0
        private set

    override fun expectEcho(issueId: String, token: String) {
        synchronized(pending) { pending += issueId to token }
    }

    override fun cancelEcho(issueId: String, token: String) {
        synchronized(pending) { pending -= issueId to token }
    }

    /** How many expectations are outstanding — for tests and diagnosis only. */
    fun pendingCount(): Int = synchronized(pending) { pending.size }

    /**
     * Classify one record, consuming the matching expectation on an
     * [Classification.ECHO]. Emits nothing — [admit] owns the event and the
     * counters, so a caller classifying a record twice does not double-count it
     * (it does, however, consume the expectation the first time; that is what
     * makes the suppression one-shot).
     */
    fun classify(record: ChangeRecord): Classification {
        // The token has to be on the `to_` side: a REMOVED row has no `to_`
        // side at all, so a removal is never an echo — the applier imposes
        // values, it does not delete issues.
        val written = stringField(record.newMetadata, CN_ECHO_FIELD) ?: return Classification.LOCAL
        // The stamped-row case (feature clause 3): the token is present but
        // this commit did not write it, so whatever this commit DID write is a
        // genuine local edit.
        if (stringField(record.oldMetadata, CN_ECHO_FIELD) == written) return Classification.LOCAL
        val matched = synchronized(pending) { pending.remove(record.issueId to written) }
        return if (matched) Classification.ECHO else Classification.LOCAL
    }

    /**
     * Classify [records] in order, report each one as
     * [MirrorEvent.RecordClassified], and return the [Classification.LOCAL]
     * ones in their original order — the list the projector sees.
     *
     * One event per record, echoes included: a suppression nobody can observe
     * is a suppression nobody can debug (feature clause 5).
     */
    fun admit(records: List<ChangeRecord>): List<ChangeRecord> {
        val admitted = ArrayList<ChangeRecord>(records.size)
        for (record in records) {
            val classification = classify(record)
            if (classification == Classification.ECHO) echoCount++ else { localCount++; admitted += record }
            onEvent(
                MirrorEvent.RecordClassified(
                    commitHash = record.commitHash,
                    issueId = record.issueId,
                    classification = classification,
                    cnDot = provenance(record, CN_DOT_FIELD),
                    cnEcho = provenance(record, CN_ECHO_FIELD),
                    workspaceIdentity = workspaceIdentity,
                ),
            )
        }
        return admitted
    }

    /**
     * [field] as the record reports it, for the event only: the `to_` side, or
     * the `from_` side of a [DiffType.REMOVED] row, which has no `to_` side.
     * Never consulted by [classify].
     */
    private fun provenance(record: ChangeRecord, field: String): String? =
        stringField(record.newMetadata, field)
            ?: if (record.diffType == DiffType.REMOVED) stringField(record.oldMetadata, field) else null

    private companion object {

        /** The per-import echo token the applier writes (decision 6wc.3-D1). */
        const val CN_ECHO_FIELD: String = "cn_echo"

        /** The provenance the applier writes beside it — reported, never matched on. */
        const val CN_DOT_FIELD: String = "cn_dot"

        /**
         * [field] of [metadata] when it is a JSON **string**. Anything else —
         * a number, an object, JSON null, an absent key — is not a shape this
         * envelope can carry and is treated as absent rather than coerced.
         */
        fun stringField(metadata: JsonObject?, field: String): String? =
            (metadata?.get(field) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
