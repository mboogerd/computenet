package civictech.economy

import civictech.cell.ClaimClass
import civictech.cell.link.IssuerId

/**
 * A validated [EconomicPolicy], read-only. The `internal` constructor means the only way to
 * obtain one is [EconomicPolicy.applied] — holding an `AppliedPolicy` is proof its backing
 * [policy] already passed [EconomicPolicy.validate] (BS-23).
 *
 * Every accessor here is total: a class or issuer absent from [policy] never throws, it
 * answers the documented default. `AppliedPolicy` adds no behaviour beyond lookup — the
 * ledger (F2 T2/T3, `TokenBucketLedger`) and the startup record (F2 T4, `StartupRecord`) are
 * both built on these accessors without editing this file.
 */
class AppliedPolicy internal constructor(val policy: EconomicPolicy) {

    /** The price for [c], or 0 when [c] has no entry in [EconomicPolicy.prices]. */
    fun price(c: ClaimClass): Long = policy.prices[c] ?: 0

    /** The capacity for [c], or 0 when [c] has no entry in [EconomicPolicy.capacities]. */
    fun capacity(c: ClaimClass): Long = policy.capacities[c] ?: 0

    /** The refill rule for [c], or `null` when [c] has no entry in [EconomicPolicy.refill]. */
    fun refill(c: ClaimClass): EconomicPolicy.Refill? = policy.refill[c]

    /**
     * The bootstrap row for [issuer], keyed by [IssuerId.name] (`kwhw6-D1`); `null` for a
     * `null` issuer or an issuer with no configured row.
     */
    fun issuerRow(issuer: IssuerId?): EconomicPolicy.IssuerBudget? = issuer?.name?.let { policy.issuers[it] }

    /** The unvouched bootstrap for [c], or 0 when [c] has no entry in [EconomicPolicy.unvouchedBootstrap]. */
    fun unvouchedBootstrap(c: ClaimClass): Long = policy.unvouchedBootstrap[c] ?: 0

    /** [EconomicPolicy.retention], forwarded. */
    val retention: EconomicPolicy.Retention get() = policy.retention

    /** [EconomicPolicy.checkpoint], forwarded. */
    val checkpoint: EconomicPolicy.Checkpoint? get() = policy.checkpoint

    /** [EconomicPolicy.label], forwarded. */
    val label: String get() = policy.label

    /** [EconomicPolicy.fitted], forwarded. */
    val fitted: Boolean get() = policy.fitted
}
