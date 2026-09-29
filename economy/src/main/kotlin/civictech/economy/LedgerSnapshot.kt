package civictech.economy

import civictech.cell.ClaimClass
import civictech.cell.DenialReason
import civictech.cell.link.PeerId

/**
 * A point-in-time read of a [TokenBucketLedger] (`kwhw6-D6`, `[ECO1-POL-07]`): plain data,
 * no function-typed field, so it can be printed, compared and carried into a findings row.
 *
 * **Consistency:** each [BucketView] is read under its bucket's lock, so one bucket's
 * `balance`/`held` pair is never torn. The bucket map itself is iterated weakly-consistently
 * and the counters are read one by one, so a snapshot taken while charges are in flight may
 * mix states from slightly different instants across buckets and counters. That is enough for
 * a findings row; it is not a transactional export (durable `LedgerState` is F6's).
 *
 * @property scope the ledger's scope, as stamped on every refusal it issues.
 * @property admitted admitted charge outcomes per [ClaimClass]; every class is present (0 when
 *   none was admitted). Monotonic.
 * @property denied refused charge outcomes per [ClaimClass] per [DenialReason]; only
 *   classes and reasons that occurred at least once are present. Monotonic.
 * @property bucketCount the number of live buckets.
 * @property buckets one view per live bucket, in no particular order.
 */
data class LedgerSnapshot(
    val scope: String,
    val admitted: Map<ClaimClass, Long>,
    val denied: Map<ClaimClass, Map<DenialReason, Long>>,
    val bucketCount: Int,
    val buckets: List<BucketView>,
) {
    /** The view of [peer]'s [claimClass] bucket, or `null` when no such bucket exists. */
    fun bucket(peer: PeerId, claimClass: ClaimClass): BucketView? =
        buckets.firstOrNull { it.peer == peer && it.claimClass == claimClass }
}

/**
 * One bucket, as read under its lock.
 *
 * @property balance the spendable tokens.
 * @property held the tokens reserved by outstanding holds (counted toward capacity, never
 *   refilled while held).
 * @property bootstrapLevel the bootstrap the bucket was created with.
 */
data class BucketView(
    val peer: PeerId,
    val claimClass: ClaimClass,
    val balance: Long,
    val held: Long,
    val bootstrapLevel: Long,
)
