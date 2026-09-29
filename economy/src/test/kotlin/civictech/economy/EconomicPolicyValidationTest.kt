package civictech.economy

import civictech.cell.ClaimClass
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * BS-23 / `[ECO1-POL-02]` / `[ECO1-POL-03]`: `EconomicPolicy.applied()` throws
 * [InvalidEconomicPolicyException] for every violation class `EconomicPolicy.validate()`
 * knows about, and the thrown message names every offending field — never a default silently
 * substituted for an invalid value.
 */
class EconomicPolicyValidationTest {

    private fun basePolicy(): EconomicPolicy = EconomicPolicy(
        prices = mapOf(ClaimClass.Spawn to 1L),
        capacities = mapOf(ClaimClass.Spawn to 10L),
        refill = mapOf(ClaimClass.Spawn to EconomicPolicy.Refill(tokensPerInterval = 1, intervalNanos = 1_000_000_000)),
        retention = EconomicPolicy.Retention(idleNanos = 1_000, maxBuckets = 10, recentKeys = 4),
        label = "test",
        fitted = false,
    )

    @Test
    fun `negative price is refused`() {
        val policy = basePolicy().copy(prices = mapOf(ClaimClass.Spawn to -1L))
        val ex = assertThrows<InvalidEconomicPolicyException> { policy.applied() }
        ex.message.shouldContain("prices[Spawn]")
    }

    @Test
    fun `a priced class with zero capacity is refused`() {
        val policy = basePolicy().copy(capacities = emptyMap())
        val ex = assertThrows<InvalidEconomicPolicyException> { policy.applied() }
        ex.message.shouldContain("capacities[Spawn]")
    }

    @Test
    fun `a priced class with negative capacity is refused`() {
        val policy = basePolicy().copy(capacities = mapOf(ClaimClass.Spawn to -5L))
        val ex = assertThrows<InvalidEconomicPolicyException> { policy.applied() }
        ex.message.shouldContain("capacities[Spawn]")
    }

    @Test
    fun `a capacitied class with a missing refill entry is refused`() {
        val policy = basePolicy().copy(refill = emptyMap())
        val ex = assertThrows<InvalidEconomicPolicyException> { policy.applied() }
        ex.message.shouldContain("refill[Spawn]")
    }

    @Test
    fun `a refill with non-positive tokensPerInterval is refused`() {
        val policy = basePolicy().copy(
            refill = mapOf(ClaimClass.Spawn to EconomicPolicy.Refill(tokensPerInterval = 0, intervalNanos = 1_000)),
        )
        val ex = assertThrows<InvalidEconomicPolicyException> { policy.applied() }
        ex.message.shouldContain("refill[Spawn].tokensPerInterval")
    }

    @Test
    fun `a refill with non-positive intervalNanos is refused`() {
        val policy = basePolicy().copy(
            refill = mapOf(ClaimClass.Spawn to EconomicPolicy.Refill(tokensPerInterval = 1, intervalNanos = 0)),
        )
        val ex = assertThrows<InvalidEconomicPolicyException> { policy.applied() }
        ex.message.shouldContain("refill[Spawn].intervalNanos")
    }

    @Test
    fun `a negative unvouched bootstrap is refused`() {
        val policy = basePolicy().copy(unvouchedBootstrap = mapOf(ClaimClass.Spawn to -1L))
        val ex = assertThrows<InvalidEconomicPolicyException> { policy.applied() }
        ex.message.shouldContain("unvouchedBootstrap[Spawn]")
    }

    @Test
    fun `an unvouched bootstrap above capacity is refused`() {
        val policy = basePolicy().copy(unvouchedBootstrap = mapOf(ClaimClass.Spawn to 100L))
        val ex = assertThrows<InvalidEconomicPolicyException> { policy.applied() }
        ex.message.shouldContain("unvouchedBootstrap[Spawn]")
    }

    @Test
    fun `an issuer bootstrap above capacity is refused and names the issuer`() {
        val policy = basePolicy().copy(
            issuers = mapOf("anchor-a" to EconomicPolicy.IssuerBudget(bootstrap = mapOf(ClaimClass.Spawn to 100L))),
        )
        val ex = assertThrows<InvalidEconomicPolicyException> { policy.applied() }
        ex.message.shouldContain("issuers[anchor-a].bootstrap[Spawn]")
    }

    @Test
    fun `a negative issuer bootstrap is refused`() {
        val policy = basePolicy().copy(
            issuers = mapOf("anchor-a" to EconomicPolicy.IssuerBudget(bootstrap = mapOf(ClaimClass.Spawn to -1L))),
        )
        val ex = assertThrows<InvalidEconomicPolicyException> { policy.applied() }
        ex.message.shouldContain("issuers[anchor-a].bootstrap[Spawn]")
    }

    @Test
    fun `an aggregateHoldCap below the Retention price is refused`() {
        val policy = basePolicy().copy(
            prices = mapOf(ClaimClass.Spawn to 1L, ClaimClass.Retention to 50L),
            capacities = mapOf(ClaimClass.Spawn to 10L, ClaimClass.Retention to 100L),
            refill = mapOf(
                ClaimClass.Spawn to EconomicPolicy.Refill(1, 1_000_000_000),
                ClaimClass.Retention to EconomicPolicy.Refill(1, 1_000_000_000),
            ),
            issuers = mapOf(
                "anchor-a" to EconomicPolicy.IssuerBudget(bootstrap = emptyMap(), aggregateHoldCap = 10L),
            ),
        )
        val ex = assertThrows<InvalidEconomicPolicyException> { policy.applied() }
        ex.message.shouldContain("issuers[anchor-a].aggregateHoldCap")
    }

    @Test
    fun `a non-positive aggregateHoldCap is refused`() {
        val policy = basePolicy().copy(
            issuers = mapOf(
                "anchor-a" to EconomicPolicy.IssuerBudget(bootstrap = emptyMap(), aggregateHoldCap = 0L),
            ),
        )
        val ex = assertThrows<InvalidEconomicPolicyException> { policy.applied() }
        ex.message.shouldContain("issuers[anchor-a].aggregateHoldCap")
    }

    @Test
    fun `non-positive retention bounds are refused`() {
        val idle = basePolicy().copy(retention = EconomicPolicy.Retention(idleNanos = 0, maxBuckets = 10, recentKeys = 4))
        assertThrows<InvalidEconomicPolicyException> { idle.applied() }.message.shouldContain("retention.idleNanos")

        val buckets = basePolicy().copy(retention = EconomicPolicy.Retention(idleNanos = 1_000, maxBuckets = 0, recentKeys = 4))
        assertThrows<InvalidEconomicPolicyException> { buckets.applied() }.message.shouldContain("retention.maxBuckets")

        val keys = basePolicy().copy(retention = EconomicPolicy.Retention(idleNanos = 1_000, maxBuckets = 10, recentKeys = -1))
        assertThrows<InvalidEconomicPolicyException> { keys.applied() }.message.shouldContain("retention.recentKeys")
    }

    @Test
    fun `a non-positive checkpoint bound is refused`() {
        val cadence = basePolicy().copy(checkpoint = EconomicPolicy.Checkpoint(cadenceNanos = 0, stalenessBoundNanos = 1_000))
        assertThrows<InvalidEconomicPolicyException> { cadence.applied() }.message.shouldContain("checkpoint.cadenceNanos")

        val staleness = basePolicy().copy(checkpoint = EconomicPolicy.Checkpoint(cadenceNanos = 1_000, stalenessBoundNanos = 0))
        assertThrows<InvalidEconomicPolicyException> { staleness.applied() }.message.shouldContain("checkpoint.stalenessBoundNanos")
    }

    @Test
    fun `a policy with two violations names both fields`() {
        val policy = basePolicy().copy(
            prices = mapOf(ClaimClass.Spawn to -1L),
            retention = EconomicPolicy.Retention(idleNanos = 0, maxBuckets = 10, recentKeys = 4),
        )
        val ex = assertThrows<InvalidEconomicPolicyException> { policy.applied() }
        ex.message.shouldContain("prices[Spawn]")
        ex.message.shouldContain("retention.idleNanos")
    }

    @Test
    fun `placeholder applies successfully and is unfitted`() {
        val applied = EconomicPolicy.placeholder().applied()
        applied.fitted shouldBe false
    }

    @Test
    fun `placeholder validates clean`() {
        EconomicPolicy.placeholder().validate().shouldBeEmpty()
    }
}
