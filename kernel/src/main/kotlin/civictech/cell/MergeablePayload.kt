package civictech.cell

import civictech.nature.MergeClass

/** Payloads declaring the associative merge used by a saturated intake. */
interface MergeablePayload {
    fun mergeWith(other: MergeablePayload): MergeablePayload
}

/**
 * The refusal `[KE1-04]` raises: an embedded value whose `mergeWith` is
 * classified [MergeClass.NON_IDEMPOTENT] was offered to a structure that folds
 * concurrent values with it.
 *
 * A subtype of [IllegalArgumentException] so an existing caller's
 * argument-validation handling catches it; the type exists so a test — and a
 * host's dead-letter accounting — can name the refusal rather than pattern-match
 * on a message.
 */
class NonIdempotentEmbeddedMerge(
    /** Fully-qualified name of the refused value's class. */
    val valueType: String,
    /** Where the refusal fired: `"put"`, `"applyRemote"`, … */
    val site: String,
) : IllegalArgumentException(
    "Refusing embedded value of type $valueType at $site: its mergeWith is classified " +
        "${MergeClass.NON_IDEMPOTENT} on ${MergeClass.NON_IDEMPOTENT.axis}. " +
        "A tagged-map key can carry several concurrent live dots, and the exposed value folds " +
        "them with mergeWith; a non-idempotent fold double-counts every gossip redelivery and " +
        "cannot be reset-removed without a dot per increment. That is the Riak embedded-counter " +
        "anomaly, and the value is refused rather than folded. Use an idempotent embedded CRDT " +
        "(civictech.cell.data.delta.PnCounterDelta — pointwise max) instead of " +
        "civictech.cell.data.delta.CounterDelta's plain addition.",
)

/**
 * `[KE1-04]`/`[KE1-10]` (j2x.1-D1) — the merge class of an **embedded value**,
 * expressed in the existing [MergeClass] vocabulary rather than a second
 * classification mechanism.
 *
 * **This is a nominated classification, not a derivation.** Idempotence of an
 * arbitrary `mergeWith` is not decidable from the type, and `V` is erased at the
 * ports of a generic structure like [civictech.cell.data.OrMapCell], so neither
 * CP-F2's KSP marker scan nor a link-time reconcile can see it: CP-F2 stamps
 * `MERGE_IDEMPOTENCE` per **cell** (a cell implementing
 * [civictech.cell.data.Replicable] offers `IDEMPOTENT` on every port), which
 * says nothing about the type argument its deltas carry. So this table names the
 * repo's own [MergeablePayload] implementations, and only those:
 *
 * - [MergeClass.NON_IDEMPOTENT] — `CounterDelta`, whose merge is plain
 *   `amount + amount` addition.
 * - [MergeClass.IDEMPOTENT] — `PnCounterDelta` (pointwise max), `SetDelta`
 *   (tag union), `WatermarkDelta` (pointwise max), `TaggedMapDelta` (dot union).
 *
 * **The residual is real and is filed, not papered over.** [classify] returns
 * `null` for any [MergeablePayload] this table does not name — an
 * implementation outside the kernel, or one added without extending the table —
 * and an unclassified value is **admitted**. So `[KE1-04]` holds over the
 * classified set only; it is not the universal guarantee "the cell rejects all
 * non-idempotent values". See `concord/corpus/DISPUTES.md` §`[KE1-04]/[KE1-10]`.
 */
object EmbeddedMergeClass {

    private const val COUNTER_DELTA = "civictech.cell.data.delta.CounterDelta"

    /** Nominated non-idempotent merges: plain addition, double-counts on a mesh. */
    private val NON_IDEMPOTENT: Set<String> = setOf(COUNTER_DELTA)

    /** Nominated idempotent merges: pointwise max, union, dot union. */
    private val IDEMPOTENT: Set<String> = setOf(
        "civictech.cell.data.delta.PnCounterDelta",
        "civictech.cell.data.delta.SetDelta",
        "civictech.cell.data.delta.WatermarkDelta",
        "civictech.cell.data.delta.TaggedMapDelta",
    )

    /**
     * The nominated [MergeClass] of [value]'s merge, or `null` when [value] is
     * not a [MergeablePayload] at all (nothing folds it, so it has no merge
     * class) or is a [MergeablePayload] this table does not name (the recorded
     * residual — admitted, never silently reclassified as idempotent).
     */
    fun classify(value: Any?): MergeClass? {
        if (value !is MergeablePayload) return null
        val name = value.javaClass.name
        return when {
            name in NON_IDEMPOTENT -> MergeClass.NON_IDEMPOTENT
            name in IDEMPOTENT -> MergeClass.IDEMPOTENT
            else -> null
        }
    }

    /**
     * Refuse [value] with [NonIdempotentEmbeddedMerge] iff it is *classified*
     * [MergeClass.NON_IDEMPOTENT]. An unclassified value and a non-mergeable
     * value both pass — see this object's KDoc for why that residual is filed
     * rather than closed.
     */
    fun requireEmbeddable(value: Any?, site: String) {
        if (classify(value) == MergeClass.NON_IDEMPOTENT) {
            throw NonIdempotentEmbeddedMerge(value!!.javaClass.name, site)
        }
    }
}
