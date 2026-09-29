package civictech.economy

import civictech.cell.ClaimClass
import kotlinx.serialization.Serializable

/**
 * The static, validated shape of an economic policy for [civictech.cell.BudgetLedger]
 * implementations to be configured with (ECO1, epic `computenet-66m`, F2 `computenet-kwhw6`
 * — epic §4.6, `[ECO1-POL-01]`..`[ECO1-POL-04]`, BS-23).
 *
 * `EconomicPolicy` is pure, immutable data — no function-typed field anywhere
 * (`[ECO1-POL-04]`) — so it can be constructed, serialized, diffed and reviewed without
 * touching a ledger, a clock or an issuer's key material. It carries no behaviour beyond
 * [validate]: bucket arithmetic, bootstrap resolution and eviction are `TokenBucketLedger`'s
 * (F2 T2/T3), built on the read-only [AppliedPolicy] this class hands out through [applied].
 *
 * **Fail-closed by construction, not by convention** (BS-23, `[DSC1-KEY-07]`'s "no silent
 * self-heal" discipline): [applied] either returns a fully validated [AppliedPolicy] or
 * throws [InvalidEconomicPolicyException] naming every offending field. Nothing in this
 * module substitutes a default for an invalid value.
 *
 * @property prices the price per [ClaimClass], in tokens. A class absent from this map has
 *   price 0.
 * @property capacities the bucket capacity per [ClaimClass]. A class absent from this map has
 *   capacity 0 — deliberately a silent zero rather than a missing-entry error, so that
 *   [validate] can instead catch the case that actually matters: a *priced* class with no
 *   positive capacity (violation 2 below).
 * @property refill how each [ClaimClass]'s bucket refills over time. Required for every class
 *   whose [capacities] entry is positive (violation 3 below); irrelevant for a class with no
 *   capacity.
 * @property retention how the ledger ages out and bounds idle buckets.
 * @property checkpoint how often (and how stale) a durable checkpoint of ledger state may be
 *   ([kwhw6-D2]) — declared and validated here, consumed by nothing in this feature.
 * @property issuers per-issuer bootstrap and aggregate hold-cap rows, keyed by the issuer's
 *   [civictech.cell.link.IssuerId.name] string rather than the `IssuerId` type itself
 *   (`kwhw6-D1`): `IssuerId`'s own KDoc says no serializer carries it, and keying by the name
 *   string keeps that literally true without writing a `KSerializer<IssuerId>`.
 * @property unvouchedBootstrap the bootstrap granted to a claim with no accepted issuer row
 *   (an unauthenticated or unvouched stamp).
 * @property label a human-readable name for this policy, e.g. identifying which fitted
 *   configuration (or [placeholder]) is in effect.
 * @property fitted `true` once real numbers (PLC2, `computenet-z1w`) have replaced
 *   [placeholder]'s stand-in values.
 */
@Serializable
data class EconomicPolicy(
    val prices: Map<ClaimClass, Long>,
    val capacities: Map<ClaimClass, Long>,
    val refill: Map<ClaimClass, Refill>,
    val retention: Retention,
    val checkpoint: Checkpoint? = null,
    val issuers: Map<String, IssuerBudget> = emptyMap(),
    val unvouchedBootstrap: Map<ClaimClass, Long> = emptyMap(),
    val label: String,
    val fitted: Boolean,
) {
    /** Token-bucket refill for one [ClaimClass]: [tokensPerInterval] tokens every [intervalNanos]. */
    @Serializable
    data class Refill(val tokensPerInterval: Long, val intervalNanos: Long)

    /**
     * Ledger-wide retention and sizing.
     *
     * @property idleNanos how long an eligible bucket must sit idle before it is swept.
     * @property maxBuckets the ledger's bucket-count ceiling.
     * @property recentKeys the size of the per-bucket idempotency window (`kwhw6-D8`).
     */
    @Serializable
    data class Retention(val idleNanos: Long, val maxBuckets: Int, val recentKeys: Int)

    /** How often, and how stale, a durable checkpoint of ledger state may be (`kwhw6-D2`). */
    @Serializable
    data class Checkpoint(val cadenceNanos: Long, val stalenessBoundNanos: Long)

    /**
     * One issuer's bootstrap row.
     *
     * @property bootstrap the bootstrap this issuer grants per [ClaimClass].
     * @property aggregateHoldCap the ceiling on this issuer's summed [ClaimClass.Retention]
     *   holds across all buckets it bootstrapped (`kwhw6-D5`), or `null` for no cap.
     */
    @Serializable
    data class IssuerBudget(val bootstrap: Map<ClaimClass, Long>, val aggregateHoldCap: Long? = null)

    /**
     * Every reason this policy would refuse to [applied] itself, each naming the offending
     * field path (`prices[Spawn]`, `refill[Link].intervalNanos`,
     * `issuers[anchor-a].bootstrap[Spawn]`, ...). An empty list means [applied] will succeed.
     */
    fun validate(): List<PolicyViolation> {
        val violations = mutableListOf<PolicyViolation>()

        // 1. a negative price.
        prices.forEach { (claimClass, price) ->
            if (price < 0) {
                violations += PolicyViolation("prices[${claimClass.name}]", "negative price: $price")
            }
        }

        // 2. a zero or negative capacity for a class whose price is > 0 (a class with no
        // capacity entry counts as capacity 0). This is also where a priced class with a
        // price entry but no capacity entry is caught — not as a silent default.
        prices.filterValues { it > 0 }.keys.forEach { claimClass ->
            val capacity = capacities[claimClass] ?: 0
            if (capacity <= 0) {
                violations += PolicyViolation(
                    "capacities[${claimClass.name}]",
                    "priced class has no positive capacity: $capacity",
                )
            }
        }

        // 3. a refill that cannot reach capacity: for a class with capacity > 0, a missing
        // refill entry, a non-positive tokensPerInterval, or a non-positive intervalNanos.
        capacities.filterValues { it > 0 }.forEach { (claimClass, capacity) ->
            val r = refill[claimClass]
            when {
                r == null -> violations += PolicyViolation(
                    "refill[${claimClass.name}]",
                    "capacity $capacity has no refill entry",
                )

                r.tokensPerInterval <= 0 -> violations += PolicyViolation(
                    "refill[${claimClass.name}].tokensPerInterval",
                    "non-positive tokensPerInterval: ${r.tokensPerInterval}",
                )

                r.intervalNanos <= 0 -> violations += PolicyViolation(
                    "refill[${claimClass.name}].intervalNanos",
                    "non-positive intervalNanos: ${r.intervalNanos}",
                )
            }
        }

        // 4. a bootstrap above capacity, in any issuer row or in unvouchedBootstrap; a
        // negative bootstrap.
        fun checkBootstrap(fieldPrefix: String, bootstrap: Map<ClaimClass, Long>) {
            bootstrap.forEach { (claimClass, amount) ->
                val field = "$fieldPrefix[${claimClass.name}]"
                if (amount < 0) {
                    violations += PolicyViolation(field, "negative bootstrap: $amount")
                } else {
                    val capacity = capacities[claimClass] ?: 0
                    if (amount > capacity) {
                        violations += PolicyViolation(field, "bootstrap $amount exceeds capacity $capacity")
                    }
                }
            }
        }
        checkBootstrap("unvouchedBootstrap", unvouchedBootstrap)
        issuers.forEach { (issuerName, issuerBudget) ->
            checkBootstrap("issuers[$issuerName].bootstrap", issuerBudget.bootstrap)

            // 5. an aggregateHoldCap below prices[Retention] (the largest single hold) or <= 0.
            val cap = issuerBudget.aggregateHoldCap
            if (cap != null) {
                val field = "issuers[$issuerName].aggregateHoldCap"
                val retentionPrice = prices[ClaimClass.Retention] ?: 0
                if (cap <= 0) {
                    violations += PolicyViolation(field, "non-positive aggregateHoldCap: $cap")
                } else if (cap < retentionPrice) {
                    violations += PolicyViolation(
                        field,
                        "aggregateHoldCap $cap is below the Retention price $retentionPrice",
                    )
                }
            }
        }

        // 6. retention/checkpoint bounds.
        if (retention.idleNanos <= 0) {
            violations += PolicyViolation("retention.idleNanos", "non-positive idleNanos: ${retention.idleNanos}")
        }
        if (retention.maxBuckets <= 0) {
            violations += PolicyViolation("retention.maxBuckets", "non-positive maxBuckets: ${retention.maxBuckets}")
        }
        if (retention.recentKeys < 0) {
            violations += PolicyViolation("retention.recentKeys", "negative recentKeys: ${retention.recentKeys}")
        }
        checkpoint?.let { cp ->
            if (cp.cadenceNanos <= 0) {
                violations += PolicyViolation(
                    "checkpoint.cadenceNanos",
                    "non-positive cadenceNanos: ${cp.cadenceNanos}",
                )
            }
            if (cp.stalenessBoundNanos <= 0) {
                violations += PolicyViolation(
                    "checkpoint.stalenessBoundNanos",
                    "non-positive stalenessBoundNanos: ${cp.stalenessBoundNanos}",
                )
            }
        }

        return violations
    }

    /**
     * Validates this policy and, if it passes, wraps it in an [AppliedPolicy] — the only way
     * one is constructed, so holding an `AppliedPolicy` is proof its backing policy validated.
     *
     * @throws InvalidEconomicPolicyException naming every offending field, never substituting
     *   a default for an invalid value (BS-23).
     */
    fun applied(): AppliedPolicy {
        val violations = validate()
        if (violations.isNotEmpty()) throw InvalidEconomicPolicyException(violations)
        return AppliedPolicy(this)
    }

    companion object {
        /**
         * An unfitted placeholder policy: every number here is a stand-in, documented as
         * such, until PLC2 (`computenet-z1w`) fits real ones (`kwhw6-D10`). Every
         * [ClaimClass] gets price 1, capacity 10, and a refill of 1 token per second;
         * retention ages idle buckets out after an hour, bounded to 10,000 buckets with a
         * 64-entry idempotency window; no checkpoint, no issuer rows, no unvouched
         * bootstrap. [validate] on this policy is always empty and [fitted] is `false`.
         */
        fun placeholder(): EconomicPolicy {
            val classes = ClaimClass.entries
            return EconomicPolicy(
                prices = classes.associateWith { 1L },
                capacities = classes.associateWith { 10L },
                refill = classes.associateWith { Refill(tokensPerInterval = 1, intervalNanos = 1_000_000_000) },
                retention = Retention(idleNanos = 3_600_000_000_000, maxBuckets = 10_000, recentKeys = 64),
                checkpoint = null,
                issuers = emptyMap(),
                unvouchedBootstrap = emptyMap(),
                label = "placeholder (unfitted; PLC2 computenet-z1w has not landed)",
                fitted = false,
            )
        }
    }
}

/** One reason an [EconomicPolicy] failed [EconomicPolicy.validate], naming the offending field path. */
data class PolicyViolation(val field: String, val message: String)

/**
 * Thrown by [EconomicPolicy.applied] when [EconomicPolicy.validate] is non-empty. [message]
 * names every offending field path, never just the first (BS-23).
 */
class InvalidEconomicPolicyException(val violations: List<PolicyViolation>) :
    Exception("Invalid EconomicPolicy: " + violations.joinToString("; ") { "${it.field}: ${it.message}" })
