package civictech.economy

import civictech.cell.BudgetClaim
import civictech.cell.BudgetLedger
import civictech.cell.BudgetOutcome
import civictech.cell.ClaimClass
import civictech.cell.DenialReason
import civictech.cell.link.AuthLevel
import civictech.cell.link.IssuerId
import civictech.cell.link.PeerId
import civictech.cell.link.PeerStamp
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder

/**
 * The phase-1 [BudgetLedger]: one token bucket per `(PeerId, ClaimClass)`, with price,
 * capacity, refill and bootstrap read from [policy] only (`[ECO1-BUD-01]`, `[ECO1-CHG-09]`:
 * this file holds no literal price). ECO1 F2 T2 (`computenet-kwhw6.2`), decisions
 * `kwhw6-D3`..`kwhw6-D7`.
 *
 * **Not a cell, not replicated** (`[ECO1-PAR-02]`): budget state is per-host and local; it
 * implements neither `civictech.cell.Cell` nor `civictech.cell.data.Replicable`.
 *
 * **Time comes only from [clock]** — an injected monotonic nanosecond source. This module
 * never reads a wall clock (`kwhw6-D12`, `NoWallClockTest`).
 *
 * **Bucket arithmetic** (`kwhw6-D3`): invariant `0 <= balance` and `balance + held <=
 * capacity`. On every access the bucket refills by whole intervals elapsed since
 * `lastRefillNanos`, capped at `capacity - held`, and the partial-interval remainder is
 * carried (`lastRefillNanos` advances by whole intervals only), so n one-interval advances
 * equal one n-interval advance (`[ECO1-BUD-06]`/`[ECO1-BUD-07]`, BS-03). A debit spends
 * `price`; a hold (`claim.hold`) moves `price` from `balance` to `held`, where refill never
 * tops it up (`66m-D2`, `66m-D7`).
 *
 * **Bootstrap reads the stamp only** (`kwhw6-D4`, `[ECO1-BUD-08]`, `[ECO1-MINT-02r/03r]`): a
 * first charge resolves the issuer row named by `stamp.issuer` when `stamp.auth ==
 * Authenticated` and the policy names that issuer, else `unvouchedBootstrap`. A resolved zero
 * is `Refused(BUDGET_NOT_GRANTED, shortfall = null)` and creates no bucket
 * (`[ECO1-BUD-10]`, `[ECO1-MINT-04r]`).
 *
 * **Aggregate hold cap** (`kwhw6-D5`, `[ECO1-DEN-11]`): holds on buckets an issuer row
 * bootstrapped are summed per issuer; a hold that would push the sum over the row's
 * `aggregateHoldCap` is refused with the fixed detail [AGGREGATE_HOLD_CAP_DETAIL], which
 * names the aggregate and never another principal's balance.
 *
 * **Fail-closed** (`kwhw6-D7`, `[ECO1-CHG-10]`/`[ECO1-CHG-11]`): any `Exception` escaping the
 * charge body becomes `Refused(LEDGER_FAILURE)`; [charge] never throws an `Exception` and a
 * refusal consumes no budget. `Error`s propagate, as everywhere in the kernel.
 *
 * **Concurrency:** [charge] and every `undo` are safe from any thread. A bucket is mutated
 * only under its own monitor; the per-issuer hold sum is updated atomically
 * ([ConcurrentHashMap.compute]) inside that same critical section, so the aggregate never
 * drifts from the holds it accounts. Lock order is always bucket, then the issuer-sum bin.
 *
 * Not yet here (F2 T3, `computenet-kwhw6.3`): the idempotency window, retention/eviction and
 * `maxBuckets`; they amend [admitOrCreate]. Buckets already track `lastAccessNanos` for them.
 *
 * @param policy the validated policy; the sole source of every number this ledger uses.
 * @param clock a monotonic nanosecond source (not wall time). Read once per charge.
 * @param scope stamped on every [BudgetOutcome.Refused], e.g. a host reference.
 */
class TokenBucketLedger(
    private val policy: AppliedPolicy,
    private val clock: () -> Long,
    val scope: String,
) : BudgetLedger {

    private data class BucketKey(val peer: PeerId, val claimClass: ClaimClass)

    /** Mutated only under `synchronized(this)`. */
    private class Bucket(
        val capacity: Long,
        val refill: EconomicPolicy.Refill?,
        val bootstrapLevel: Long,
        /** The issuer row that bootstrapped this bucket, or `null` for unvouched (`kwhw6-D5`). */
        val issuerName: String?,
        now: Long,
    ) {
        var balance: Long = bootstrapLevel
        var held: Long = 0
        var lastRefillNanos: Long = now
        var lastAccessNanos: Long = now
    }

    private val buckets = ConcurrentHashMap<BucketKey, Bucket>()
    private val heldByIssuer = ConcurrentHashMap<String, Long>()
    private val admittedCounts: Map<ClaimClass, LongAdder> =
        EnumMap<ClaimClass, LongAdder>(ClaimClass::class.java).apply {
            ClaimClass.entries.forEach { put(it, LongAdder()) }
        }
    private val deniedCounts = ConcurrentHashMap<Pair<ClaimClass, DenialReason>, LongAdder>()

    override fun charge(claim: BudgetClaim): BudgetOutcome =
        try {
            chargeUnguarded(claim)
        } catch (e: Exception) {
            refuse(claim.claimClass, DenialReason.LEDGER_FAILURE, null, "${e::class.simpleName}: ${e.message}")
        }

    private fun chargeUnguarded(claim: BudgetClaim): BudgetOutcome {
        val claimClass = claim.claimClass
        val price = policy.price(claimClass)
        val now = clock()

        val bucket = admitOrCreate(claim, now)
            ?: return refuse(claimClass, DenialReason.BUDGET_NOT_GRANTED, null, "no bootstrap for $claimClass")

        synchronized(bucket) {
            bucket.lastAccessNanos = now
            refill(bucket, now)

            if (price == 0L) {
                // A zero-price class is a policy choice, not a bypass: bootstrap was still
                // resolved above (and could refuse); an admitted zero charge mutates nothing.
                admittedCounts.getValue(claimClass).increment()
                return BudgetOutcome.Admitted {}
            }

            if (claim.hold) {
                val issuer = bucket.issuerName
                val cap = issuer?.let { policy.issuerRow(IssuerId(it))?.aggregateHoldCap }
                if (issuer != null && cap != null) {
                    // Check the aggregate, then the balance, and account the hold — all in one
                    // atomic update of the issuer's sum, so two principals of one issuer cannot
                    // both slip under the cap.
                    var refusal: BudgetOutcome.Refused? = null
                    heldByIssuer.compute(issuer) { _, current ->
                        val sum = current ?: 0L
                        when {
                            sum + price > cap -> {
                                refusal = refusal(DenialReason.BUDGET_EXHAUSTED, price - (cap - sum), AGGREGATE_HOLD_CAP_DETAIL)
                                current
                            }
                            price > bucket.balance -> {
                                refusal = refusal(DenialReason.BUDGET_EXHAUSTED, price - bucket.balance, null)
                                current
                            }
                            else -> sum + price
                        }
                    }
                    refusal?.let { return countDenied(claimClass, it) }
                } else if (price > bucket.balance) {
                    return refuse(claimClass, DenialReason.BUDGET_EXHAUSTED, price - bucket.balance, null)
                }
                bucket.balance -= price
                bucket.held += price
                admittedCounts.getValue(claimClass).increment()
                return BudgetOutcome.Admitted(onceOnly { releaseHold(bucket, price) })
            }

            if (price > bucket.balance) {
                return refuse(claimClass, DenialReason.BUDGET_EXHAUSTED, price - bucket.balance, null)
            }
            bucket.balance -= price
            admittedCounts.getValue(claimClass).increment()
            return BudgetOutcome.Admitted(onceOnly { refundDebit(bucket, price) })
        }
    }

    /**
     * The bucket for [claim]'s `(peer, class)`, creating it on first charge from the resolved
     * bootstrap (`kwhw6-D4`); `null` when the resolved bootstrap is zero, in which case no
     * bucket is created. The single seam F2 T3 amends for the idempotency window and the
     * ledger-full path.
     */
    private fun admitOrCreate(claim: BudgetClaim, now: Long): Bucket? {
        val key = BucketKey(claim.stamp.id, claim.claimClass)
        buckets[key]?.let { return it }
        val (bootstrap, issuerName) = resolveBootstrap(claim.stamp, claim.claimClass)
        if (bootstrap <= 0L) return null
        return buckets.computeIfAbsent(key) {
            Bucket(
                capacity = policy.capacity(claim.claimClass),
                refill = policy.refill(claim.claimClass),
                bootstrapLevel = bootstrap,
                issuerName = issuerName,
                now = now,
            )
        }
    }

    /** `kwhw6-D4`: the stamp alone decides; nothing else about the principal is consulted. */
    private fun resolveBootstrap(stamp: PeerStamp, claimClass: ClaimClass): Pair<Long, String?> {
        val issuer = stamp.issuer
        if (stamp.auth == AuthLevel.Authenticated && issuer != null) {
            val row = policy.issuerRow(issuer)
            if (row != null) return (row.bootstrap[claimClass] ?: 0L) to issuer.name
        }
        return policy.unvouchedBootstrap(claimClass) to null
    }

    /** `kwhw6-D3`. Caller holds the bucket's monitor. */
    private fun refill(bucket: Bucket, now: Long) {
        val r = bucket.refill ?: return
        val elapsed = now - bucket.lastRefillNanos
        if (elapsed <= 0L) return
        val intervals = elapsed / r.intervalNanos
        if (intervals == 0L) return
        val room = bucket.capacity - bucket.held
        // Saturate rather than multiply once the refill alone would fill the bucket.
        val added = if (intervals > room / r.tokensPerInterval) room else intervals * r.tokensPerInterval
        bucket.balance = minOf(room, bucket.balance + added)
        bucket.lastRefillNanos += intervals * r.intervalNanos
    }

    private fun refundDebit(bucket: Bucket, price: Long) {
        synchronized(bucket) {
            bucket.balance = minOf(bucket.capacity - bucket.held, bucket.balance + price)
        }
    }

    private fun releaseHold(bucket: Bucket, price: Long) {
        synchronized(bucket) {
            bucket.held -= price
            bucket.balance += price
            bucket.issuerName?.let { issuer ->
                heldByIssuer.computeIfPresent(issuer) { _, sum -> (sum - price).takeIf { it > 0L } }
            }
        }
    }

    /** An `undo` that runs [action] at most once, from any thread, and never throws. */
    private fun onceOnly(action: () -> Unit): () -> Unit {
        val done = AtomicBoolean(false)
        return {
            if (done.compareAndSet(false, true)) {
                try {
                    action()
                } catch (_: Exception) {
                    // `undo` must never throw (BudgetOutcome.Admitted's contract).
                }
            }
        }
    }

    private fun refusal(reason: DenialReason, shortfall: Long?, detail: String?) =
        BudgetOutcome.Refused(reason, scope, shortfall, detail)

    private fun refuse(claimClass: ClaimClass, reason: DenialReason, shortfall: Long?, detail: String?) =
        countDenied(claimClass, refusal(reason, shortfall, detail))

    private fun countDenied(claimClass: ClaimClass, refused: BudgetOutcome.Refused): BudgetOutcome.Refused {
        try {
            deniedCounts.computeIfAbsent(claimClass to refused.reason) { LongAdder() }.increment()
        } catch (_: Exception) {
            // The refusal stands even if the counter cannot move (`kwhw6-D7`).
        }
        return refused
    }

    /** A plain-data read of counters and buckets (`kwhw6-D6`); see [LedgerSnapshot] for consistency. */
    fun snapshot(): LedgerSnapshot {
        val views = buckets.entries.map { (key, bucket) ->
            synchronized(bucket) {
                BucketView(key.peer, key.claimClass, bucket.balance, bucket.held, bucket.bootstrapLevel)
            }
        }
        val denied = EnumMap<ClaimClass, MutableMap<DenialReason, Long>>(ClaimClass::class.java)
        deniedCounts.forEach { (k, adder) ->
            denied.getOrPut(k.first) { EnumMap(DenialReason::class.java) }[k.second] = adder.sum()
        }
        return LedgerSnapshot(
            scope = scope,
            admitted = admittedCounts.mapValues { it.value.sum() },
            denied = denied.mapValues { it.value.toMap() },
            bucketCount = views.size,
            buckets = views,
        )
    }

    companion object {
        /** The fixed refusal detail for an issuer-aggregate hold cap (`kwhw6-D5`, `[ECO1-DEN-11]`). */
        const val AGGREGATE_HOLD_CAP_DETAIL: String = "issuer aggregate hold cap"
    }
}
