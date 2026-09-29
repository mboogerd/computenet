package civictech.cell

import civictech.cell.link.PeerId

/**
 * The shared test ledger for ECO1 F3's charge sites (`5o1rf-D10`): the host's
 * spawn walk (`BudgetHierarchyWalkTest`) and the membrane's Attention and Link
 * arms reuse it, so every site is asserted against the same arithmetic.
 *
 * - Price **1** per claim, against a per-`(principal, class)` balance that
 *   starts at [initial]. A claim against a balance below 1 is refused
 *   `BUDGET_EXHAUSTED` at [scope] with `shortfall = 1 - balance`.
 * - [refuse] runs first and, when it returns a refusal, that refusal is the
 *   answer and nothing is debited — how a test makes one scope refuse.
 * - [charges] records **every** call, including refused and deduped ones, so
 *   "the ledger received the key twice" is readable; [throwOnCharge] throws
 *   `IllegalStateException("ledger down")` *before* recording anything.
 * - With [dedupKeys], a claim whose non-null `key` was already admitted is
 *   admitted again without a debit (the `66m-D8` site-key contract).
 * - [BudgetOutcome.Admitted.undo] refunds 1 exactly once per admission,
 *   however many times it is called.
 *
 * A plain map, no clock, no refill: nothing here models `:economy`'s buckets.
 * Synchronized, because a `CoroutineScheduler`-backed host charges off the
 * test thread.
 */
class RecordingLedger(
    val scope: String,
    private val initial: Long = 10,
    val refuse: (BudgetClaim) -> BudgetOutcome.Refused? = { null },
    val throwOnCharge: Boolean = false,
    val dedupKeys: Boolean = false,
) : BudgetLedger {
    val balances: MutableMap<Pair<PeerId, ClaimClass>, Long> = mutableMapOf()
    val charges: MutableList<BudgetClaim> = mutableListOf()
    private val seenKeys = mutableSetOf<Pair<ClaimClass, Any>>()

    /** The balance for `(peer, claimClass)`, reading [initial] for one never charged. */
    @Synchronized
    fun balance(peer: PeerId, claimClass: ClaimClass): Long = balances[peer to claimClass] ?: initial

    @Synchronized
    override fun charge(claim: BudgetClaim): BudgetOutcome {
        if (throwOnCharge) throw IllegalStateException("ledger down")
        charges += claim
        refuse(claim)?.let { return it }
        val bucket = claim.stamp.id to claim.claimClass
        val key = claim.key
        if (dedupKeys && key != null && (claim.claimClass to key) in seenKeys) return BudgetOutcome.Admitted {}
        val current = balances[bucket] ?: initial
        if (current < 1) return BudgetOutcome.Refused(DenialReason.BUDGET_EXHAUSTED, scope, shortfall = 1 - current)
        balances[bucket] = current - 1
        if (dedupKeys && key != null) seenKeys += claim.claimClass to key
        var undone = false
        return BudgetOutcome.Admitted {
            synchronized(this) {
                if (!undone) {
                    undone = true
                    balances[bucket] = (balances[bucket] ?: initial) + 1
                }
            }
        }
    }
}

/**
 * A ledger that throws on any call — the `[ECO1-BUD-05]` probe: attached to
 * every scope, a LOCAL crossing must still succeed, because a local crossing
 * never constructs a claim and so never reaches a ledger.
 */
object ThrowingLedger : BudgetLedger {
    override fun charge(claim: BudgetClaim): BudgetOutcome =
        throw AssertionError("ThrowingLedger was charged: $claim")
}
