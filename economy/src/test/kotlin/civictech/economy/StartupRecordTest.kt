package civictech.economy

import civictech.cell.ClaimClass.Attention
import civictech.cell.ClaimClass.Retention
import civictech.cell.ClaimClass.Spawn
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * BS-19r, `[ECO1-BUD-09]`, `[ECO1-MINT-09r]`, `[ECO1-MINT-10r]`, `[ECO1-PAR-04]`:
 * `AppliedPolicy.startupRecord()` returns ONE record naming every budget parameter and the
 * k-hosts statement, and derives the unvouched-bootstrap and unfitted-policy warnings.
 */
class StartupRecordTest {

    private fun policyWithIssuerAndUnvouched(): EconomicPolicy = EconomicPolicy.placeholder().copy(
        issuers = mapOf(
            "anchor-a" to EconomicPolicy.IssuerBudget(
                bootstrap = mapOf(Spawn to 3L, Retention to 4L),
                aggregateHoldCap = 40L,
            ),
        ),
        unvouchedBootstrap = mapOf(Attention to 2L),
    )

    @Test
    fun `toString names every parameter, the k-hosts statement once, and the placeholder marker`() {
        val record = policyWithIssuerAndUnvouched().applied().startupRecord()
        val rendered = record.toString()

        rendered.shouldContain("issuer anchor-a")
        rendered.shouldContain("aggregateHoldCap 40")
        rendered.shouldContain("Attention=2")
        civictech.cell.ClaimClass.entries.forEach { c -> rendered.shouldContain(c.name) }
        rendered.shouldContain("retention")
        rendered.shouldContain("fitted: false (placeholder)")
        rendered.shouldContain(StartupRecord.K_HOSTS_STATEMENT)

        rendered.lines().count { it.contains(StartupRecord.K_HOSTS_STATEMENT) } shouldBe 1
    }

    @Test
    fun `unvouchedBootstrap line is present even when empty`() {
        val record = EconomicPolicy.placeholder().applied().startupRecord()
        record.toString().shouldContain("unvouchedBootstrap: {} (zero for every class)")
    }

    @Test
    fun `unvouched and unfitted warnings fire together, in order`() {
        val record = policyWithIssuerAndUnvouched().applied().startupRecord()

        record.warnings shouldHaveSize 2
        record.warnings[0].shouldContain("Attention")
        record.warnings[0].shouldContain("unbounded by identity cost")
        record.warnings[1].shouldContain("placeholder (unfitted; PLC2 computenet-z1w has not landed)")
    }

    @Test
    fun `placeholder alone yields exactly the unfitted warning`() {
        val record = EconomicPolicy.placeholder().applied().startupRecord()

        record.warnings shouldHaveSize 1
        record.warnings[0].shouldContain("unfitted placeholder")
        record.warnings[0].shouldContain("placeholder (unfitted; PLC2 computenet-z1w has not landed)")
    }

    @Test
    fun `a fitted policy with empty unvouched bootstrap yields no warnings`() {
        val policy = EconomicPolicy.placeholder().copy(fitted = true, label = "fitted-by-test")
        val record = policy.applied().startupRecord()

        record.warnings.shouldBeEmpty()
    }

    @Test
    fun `a zero-valued unvouched bootstrap entry does not warn`() {
        // Mutation check: dropping the '> 0' condition (warning on any entry, including zero)
        // would fail this case. Fitted, so only the unvouched-bootstrap warning is in play.
        val policy = EconomicPolicy.placeholder()
            .copy(fitted = true, label = "fitted-by-test", unvouchedBootstrap = mapOf(Spawn to 0L))
        val record = policy.applied().startupRecord()

        record.warnings.shouldBeEmpty()
    }

    @Test
    fun `emit calls warn with toString first then each warning in order, and never throws`() {
        val policy = policyWithIssuerAndUnvouched()
        val record = policy.applied().startupRecord()

        val collected = mutableListOf<String>()
        record.emit { collected += it }

        collected shouldContainExactly listOf(record.toString()) + record.warnings

        // A throwing sink must not propagate.
        var threw = false
        try {
            record.emit { throw IllegalStateException("boom") }
        } catch (e: Exception) {
            threw = true
        }
        threw shouldBe false
    }

    @Test
    fun `startupRecord is a pure function of the policy`() {
        val applied = policyWithIssuerAndUnvouched().applied()

        applied.startupRecord() shouldBe applied.startupRecord()
    }
}
