package civictech.economy.persist

import civictech.cell.ClaimClass
import kotlinx.serialization.Serializable

/**
 * The durable, plain-data state of one budget ledger (`66m-D9`, `xbs8t-D1`).
 *
 * Holds are recorded only as [BucketRecord.heldTotal] and are treated as spent on restore:
 * the links they backed died with the process. Idempotency windows, admitted/denied counters
 * and the per-issuer held aggregate are deliberately not persisted. A restored ledger starts
 * with empty windows and counters and with no live holds.
 */
@Serializable
data class LedgerState(
    val policyLabel: String,
    val sequence: Long,
    val wallStampMillis: Long,
    val buckets: List<BucketRecord>,
)

/** One durable `(peer, claim class)` bucket row: names and numbers only, never key material. */
@Serializable
data class BucketRecord(
    val peer: String,
    val claimClass: ClaimClass,
    val balance: Long,
    val heldTotal: Long,
    val bootstrapLevel: Long,
    val issuer: String?,
)

/** The store's total read result: absence and unreadability are distinct fallback inputs. */
sealed interface CheckpointRead {
    data class Present(val state: LedgerState) : CheckpointRead
    data object Missing : CheckpointRead
    data class Unreadable(val detail: String) : CheckpointRead
}

/** The caller-visible result of attempting to restore a ledger at startup. */
sealed interface RestoreOutcome {
    data class Restored(val sequence: Long, val buckets: Int) : RestoreOutcome
    data class Fallback(val reason: FallbackReason, val detail: String? = null) : RestoreOutcome
}

/** Why startup initialized later-created buckets from bootstrap instead of checkpoint state. */
enum class FallbackReason {
    MISSING,
    UNREADABLE,
    POLICY_MISMATCH,
    STALE,
}
