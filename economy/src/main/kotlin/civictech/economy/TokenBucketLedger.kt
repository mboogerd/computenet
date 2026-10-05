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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
 * **Idempotency window** (`kwhw6-D8`, `[ECO1-CHG-08]` ledger half): each bucket remembers the
 * keys of its last `retention.recentKeys` ADMITTED claims. A claim whose non-null `key` is in
 * that window is a replay: it returns a fresh `Admitted` with a no-op `undo`, counts as
 * admitted, and debits, holds and records nothing. `null` keys and refused claims are never
 * recorded, so they are never deduplicated. `recentKeys == 0` disables the window. The window
 * lives in the bucket, so it dies with it: a replay arriving after its bucket was evicted is
 * charged again (eviction needs `held == 0`, so no hold can be replayed twice this way).
 *
 * **Undo forgets the key** (`[ECO1-CHG-08]`, `computenet-sb9v1`): a real admission's `undo`
 * removes its own non-null key from the window, so a later claim with the same key is charged
 * again rather than admitted free — needed for the ancestor-walk rollback (`66m-D8`) to retry
 * with the same site-derived key after an undo. The replay's own `undo` is a distinct no-op
 * that never reaches this removal, so undoing a replay leaves the window, and the original
 * admission's dedup, intact. If the bucket is evicted before a debit's `undo` runs, the refund
 * lands in the unmapped `Bucket` and is lost — see [refundDebit] and "Bounded state" below;
 * this cannot grant unearned budget, only lose an already-fail-safe refund.
 *
 * **Forgetting is admission-specific** (`computenet-chgam`): the window remembers, per key, the
 * generation token of the admission that currently owns it, and an `undo` forgets the key only
 * when it still names its own token. Without this, an `undo` that runs after its key aged out of
 * the window and was reused by a later admission would forget that later admission's entry by
 * key value alone, making its own replay look like a fresh claim and charge twice — a stale
 * undo must never touch a newer admission's window entry.
 *
 * **Bounded state** (`kwhw6-D9`, `[ECO1-BUD-11]`/`[ECO1-BUD-12]`, BS-22): a bucket is
 * *eligible* for eviction when `held == 0 && balance >= bootstrapLevel` — evicting it and
 * re-creating it at bootstrap later cannot hand its principal budget it did not already have.
 * [sweep] removes every eligible bucket idle longer than `retention.idleNanos`. Creation of a
 * bucket when `retention.maxBuckets` are live sweeps first, then evicts the eligible bucket
 * with the oldest `lastAccessNanos` whatever its idle age, and if no bucket is eligible
 * refuses with `Refused(LEDGER_FAILURE, detail = `[LEDGER_FULL_DETAIL]`)`, creating nothing.
 * A drained or holding bucket is therefore never evicted, and its principal's next claim is
 * still judged against its real balance. Eligibility is judged after a lazy refill to the
 * judging instant (refill arithmetic unchanged; see [refill]), so a drained bucket that has
 * since refilled back to its bootstrap becomes eligible without first being touched.
 *
 * **Creation lock:** every bucket creation (and the eviction making room for it) runs under
 * one ledger-level [ReentrantLock], so two concurrent first charges cannot both pass the size
 * check; charges on existing buckets take only their bucket's monitor. The creating thread
 * also performs its first charge under that lock, so a concurrent creator cannot evict a
 * bucket before its first claim lands. An evicted bucket is flagged under its own monitor; a
 * charge that finds the flag re-resolves the principal's bucket, so no charge lands on a
 * bucket that has left the map. Lock order: creation lock, then bucket, then issuer-sum bin.
 * Cost: a creation on a full ledger scans every bucket (sweep, then oldest-eligible), so it
 * is O(`maxBuckets`); charges on existing buckets are unaffected.
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

        /** Set, under this monitor, when the bucket leaves the map; a charge seeing it retries. */
        var evicted: Boolean = false

        /** `kwhw6-D8`: admitted keys, oldest first, and their generation tokens; lazily built. */
        var recentOrder: ArrayDeque<Any>? = null

        /**
         * `computenet-chgam`: key -> the generation token of the admission that currently owns
         * that window entry. An `undo` forgets a key only when it still names its own token, so
         * a stale undo (whose key aged out and was reused by a later admission) cannot forget
         * that later admission's entry.
         */
        var recentTokens: HashMap<Any, Long>? = null

        /** Next generation token to assign; monotonic per bucket, never reused. */
        var nextToken: Long = 0L

        /** Caller holds the monitor and has refilled to the judging instant. */
        fun eligible(): Boolean = held == 0L && balance >= bootstrapLevel
    }

    private val buckets = ConcurrentHashMap<BucketKey, Bucket>()

    /** Held around every bucket creation and the eviction that makes room for it (`kwhw6-D9`). */
    private val creationLock = ReentrantLock()
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
        val now = clock()
        val key = BucketKey(claim.stamp.id, claim.claimClass)
        // An existing bucket is charged under its own monitor only. `null` from chargeBucket
        // means it was evicted (and unmapped) between lookup and lock: re-resolve by creating.
        buckets[key]?.let { existing -> chargeBucket(existing, claim, now)?.let { return it } }
        return createAndCharge(key, claim, now)
    }

    /**
     * The first charge for [key]: resolve the bootstrap (`kwhw6-D4`), make room under
     * `retention.maxBuckets` (`kwhw6-D9`), create the bucket and charge it — all under
     * [creationLock]. A resolved zero bootstrap is `BUDGET_NOT_GRANTED` and a full ledger with
     * nothing eligible is `LEDGER_FAILURE` ([LEDGER_FULL_DETAIL]); neither creates a bucket.
     */
    private fun createAndCharge(key: BucketKey, claim: BudgetClaim, now: Long): BudgetOutcome =
        creationLock.withLock {
            // Another creator may have won the race for this key while we waited; if that bucket
            // was evicted since, it is already unmapped and a fresh one is created below.
            buckets[key]?.let { raced -> chargeBucket(raced, claim, now)?.let { return it } }
            val (bootstrap, issuerName) = resolveBootstrap(claim.stamp, claim.claimClass)
            if (bootstrap <= 0L) {
                return refuse(claim.claimClass, DenialReason.BUDGET_NOT_GRANTED, null, "no bootstrap for ${claim.claimClass}")
            }
            if (!makeRoom(now)) {
                return refuse(claim.claimClass, DenialReason.LEDGER_FAILURE, null, LEDGER_FULL_DETAIL)
            }
            val bucket = Bucket(
                capacity = policy.capacity(claim.claimClass),
                refill = policy.refill(claim.claimClass),
                bootstrapLevel = bootstrap,
                issuerName = issuerName,
                now = now,
            )
            buckets[key] = bucket
            // Only a public [sweep] could have evicted it by now, and a bucket accessed at
            // `now` is not idle at `now`; a sweep run with a later instant is the one way.
            chargeBucket(bucket, claim, now)
                ?: refuse(claim.claimClass, DenialReason.LEDGER_FAILURE, null, "bucket evicted during creation")
        }

    /**
     * `kwhw6-D9`: true when a bucket may be created — the ledger holds fewer than
     * `retention.maxBuckets`, after a sweep and, failing that, after evicting the eligible
     * bucket with the oldest `lastAccessNanos`. Caller holds [creationLock], so the count
     * cannot rise concurrently (only creation adds).
     */
    private fun makeRoom(now: Long): Boolean {
        val max = policy.retention.maxBuckets
        if (buckets.size < max) return true
        sweep(now)
        while (buckets.size >= max) {
            var oldest: Map.Entry<BucketKey, Bucket>? = null
            var oldestAccess = Long.MAX_VALUE
            for (entry in buckets.entries) {
                val b = entry.value
                synchronized(b) {
                    if (!b.evicted) {
                        refill(b, now)
                        if (b.eligible() && (oldest == null || b.lastAccessNanos < oldestAccess)) {
                            oldest = entry
                            oldestAccess = b.lastAccessNanos
                        }
                    }
                }
            }
            val victim = oldest ?: return false
            // Re-checked under the victim's monitor: a charge may have drained it since.
            evictIf(victim.key, victim.value, now) { true }
        }
        return true
    }

    /**
     * Removes [bucket] when it is still mapped, eligible and satisfies [idle]; the whole
     * judgement and the removal happen under its monitor, so no charge interleaves.
     */
    private inline fun evictIf(key: BucketKey, bucket: Bucket, now: Long, idle: (Bucket) -> Boolean): Boolean =
        synchronized(bucket) {
            if (bucket.evicted) return false
            refill(bucket, now)
            if (!bucket.eligible() || !idle(bucket)) return false
            if (!buckets.remove(key, bucket)) return false
            bucket.evicted = true
            true
        }

    /**
     * Evicts every eligible bucket (`held == 0 && balance >= bootstrapLevel`, judged after a
     * lazy refill to [now]) idle for more than `retention.idleNanos` (`kwhw6-D9`,
     * `[ECO1-BUD-11]`). Nothing is subtracted from the issuer hold sums: an eligible bucket
     * holds nothing. [snapshot] never sweeps; it is read-only.
     *
     * @return the number of buckets evicted.
     */
    fun sweep(now: Long = clock()): Int {
        val idleNanos = policy.retention.idleNanos
        var evicted = 0
        for ((key, bucket) in buckets.entries) {
            if (evictIf(key, bucket, now) { now - it.lastAccessNanos > idleNanos }) evicted++
        }
        return evicted
    }

    /**
     * Charges [claim] against [bucket]; `null` when the bucket was evicted before its monitor
     * was taken, so the caller re-resolves. `kwhw6-D8` replays are answered before refill.
     */
    private fun chargeBucket(bucket: Bucket, claim: BudgetClaim, now: Long): BudgetOutcome? {
        val claimClass = claim.claimClass
        val price = policy.price(claimClass)
        synchronized(bucket) {
            if (bucket.evicted) return null
            bucket.lastAccessNanos = now
            val idemKey = claim.key
            if (idemKey != null && bucket.recentTokens?.containsKey(idemKey) == true) {
                admittedCounts.getValue(claimClass).increment()
                return BudgetOutcome.Admitted {}
            }
            refill(bucket, now)

            if (price == 0L) {
                // A zero-price class is a policy choice, not a bypass: bootstrap was still
                // resolved above (and could refuse); an admitted zero charge mutates no
                // balance, but its `undo` still forgets the idempotency key (below) so a
                // real admission's retry is never mistaken for the replay's own no-op undo.
                val token = recordAdmission(bucket, claim)
                // `undo` runs from any thread: take the bucket's monitor, as refundDebit and
                // releaseHold do, because forgetKey mutates the unsynchronized window.
                return BudgetOutcome.Admitted(onceOnly { synchronized(bucket) { forgetKey(bucket, claim.key, token) } })
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
                val token = recordAdmission(bucket, claim)
                return BudgetOutcome.Admitted(onceOnly { releaseHold(bucket, price, claim.key, token) })
            }

            if (price > bucket.balance) {
                return refuse(claimClass, DenialReason.BUDGET_EXHAUSTED, price - bucket.balance, null)
            }
            bucket.balance -= price
            val token = recordAdmission(bucket, claim)
            return BudgetOutcome.Admitted(onceOnly { refundDebit(bucket, price, claim.key, token) })
        }
    }

    /**
     * Counts a real admission and records its key in the bucket's window (`kwhw6-D8`),
     * evicting the oldest key beyond `retention.recentKeys`. Caller holds the bucket's monitor
     * and has already confirmed (via the replay check above) that a non-null key is not
     * currently in the window, so this always assigns a fresh entry rather than updating one.
     *
     * @return the generation token assigned to this admission's window entry, or `null` when
     * the key is not tracked (no key, or the window is disabled) — [forgetKey] uses the token
     * so a stale undo cannot forget a newer admission's reused key (`computenet-chgam`).
     */
    private fun recordAdmission(bucket: Bucket, claim: BudgetClaim): Long? {
        admittedCounts.getValue(claim.claimClass).increment()
        val idemKey = claim.key
        val window = policy.retention.recentKeys
        if (idemKey == null || window <= 0) return null
        val order = bucket.recentOrder ?: ArrayDeque<Any>().also { bucket.recentOrder = it }
        val tokens = bucket.recentTokens ?: HashMap<Any, Long>().also { bucket.recentTokens = it }
        val token = bucket.nextToken++
        tokens[idemKey] = token
        order.addLast(idemKey)
        while (order.size > window) tokens.remove(order.removeFirst())
        return token
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

    /**
     * `kwhw6-D8` fix (`computenet-sb9v1`): a debit's `undo` forgets its own idempotency key so
     * a later claim with the same key is charged again rather than treated as a replay. Only a
     * *real* admission's `undo` reaches here — the replay path (above) returns its own no-op
     * `Admitted {}` directly and never calls this, so undoing a replay leaves the window
     * untouched, as `kwhw6-D8` requires.
     *
     * If [bucket] was evicted between admission and this `undo` running, the refund lands in
     * the (now unmapped) `Bucket` object and is lost: the principal's next charge resolves a
     * fresh bucket at bootstrap. This cannot grant budget the principal did not already have
     * (eviction requires `held == 0 && balance >= bootstrapLevel`, so the lost refund is at
     * most the gap above bootstrap) — see "Bounded state" above — and is accepted rather than
     * fixed, per `computenet-sb9v1`.
     */
    private fun refundDebit(bucket: Bucket, price: Long, key: Any?, token: Long?) {
        synchronized(bucket) {
            bucket.balance = minOf(bucket.capacity - bucket.held, bucket.balance + price)
            forgetKey(bucket, key, token)
        }
    }

    /** See [refundDebit]: the same idempotency-key and evicted-bucket handling applies to a hold's release. */
    private fun releaseHold(bucket: Bucket, price: Long, key: Any?, token: Long?) {
        synchronized(bucket) {
            bucket.held -= price
            bucket.balance += price
            bucket.issuerName?.let { issuer ->
                heldByIssuer.computeIfPresent(issuer) { _, sum -> (sum - price).takeIf { it > 0L } }
            }
            forgetKey(bucket, key, token)
        }
    }

    /**
     * Removes [key] from the bucket's idempotency window, but only when it still names [token]
     * — the generation token assigned at the admission this `undo` belongs to. If the key aged
     * out of the window and was reused by a later admission (a new token), this is a no-op: a
     * stale undo must never forget a newer admission's entry (`computenet-chgam`). Caller holds
     * the bucket's monitor. `token == null` means this admission was never tracked (no key, or
     * the window was disabled), so there is nothing to forget.
     */
    private fun forgetKey(bucket: Bucket, key: Any?, token: Long?) {
        if (key == null || token == null) return
        val tokens = bucket.recentTokens ?: return
        if (tokens[key] == token) {
            tokens.remove(key)
            bucket.recentOrder?.remove(key)
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

        /** The fixed refusal detail when `retention.maxBuckets` are live and none is evictable (`kwhw6-D9`). */
        const val LEDGER_FULL_DETAIL: String = "ledger full"
    }
}
