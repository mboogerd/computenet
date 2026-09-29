package civictech.economy

import civictech.cell.ClaimClass
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * `[ECO1-POL-01]` / `[ECO1-POL-04]`: `EconomicPolicy` is one `@Serializable` type carrying no
 * function-typed field, and round-trips through kotlinx JSON.
 */
class EconomicPolicySerializationTest {

    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `placeholder round-trips through kotlinx JSON`() {
        val original = EconomicPolicy.placeholder()
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EconomicPolicy>(encoded)
        decoded shouldBe original
    }

    @Test
    fun `a policy with an issuer row and a checkpoint round-trips`() {
        val original = EconomicPolicy(
            prices = mapOf(ClaimClass.Spawn to 1L, ClaimClass.Retention to 5L),
            capacities = mapOf(ClaimClass.Spawn to 10L, ClaimClass.Retention to 20L),
            refill = mapOf(
                ClaimClass.Spawn to EconomicPolicy.Refill(1, 1_000_000_000),
                ClaimClass.Retention to EconomicPolicy.Refill(1, 1_000_000_000),
            ),
            retention = EconomicPolicy.Retention(idleNanos = 3_600_000_000_000, maxBuckets = 10_000, recentKeys = 64),
            checkpoint = EconomicPolicy.Checkpoint(cadenceNanos = 60_000_000_000, stalenessBoundNanos = 120_000_000_000),
            issuers = mapOf(
                "anchor-a" to EconomicPolicy.IssuerBudget(
                    bootstrap = mapOf(ClaimClass.Spawn to 3L),
                    aggregateHoldCap = 50L,
                ),
            ),
            unvouchedBootstrap = mapOf(ClaimClass.Spawn to 1L),
            label = "fitted-example",
            fitted = true,
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<EconomicPolicy>(encoded)
        decoded shouldBe original
    }

    /**
     * `[ECO1-POL-04]`: no declared field of `EconomicPolicy` or its nested classes has a
     * `kotlin.Function` type. Walked by reflection rather than asserted from the source text,
     * so a later field addition is checked the same way this one is.
     */
    @Test
    fun `no declared field of EconomicPolicy or its nested classes is function-typed`() {
        val types = listOf(
            EconomicPolicy::class.java,
            EconomicPolicy.Refill::class.java,
            EconomicPolicy.Retention::class.java,
            EconomicPolicy.Checkpoint::class.java,
            EconomicPolicy.IssuerBudget::class.java,
        )

        val offending = types.flatMap { type ->
            type.declaredFields
                .filterNot { it.isSynthetic }
                .filter { kotlin.Function::class.java.isAssignableFrom(it.type) }
                .map { "${type.name}.${it.name}" }
        }

        offending.shouldBeEmpty()
    }
}
