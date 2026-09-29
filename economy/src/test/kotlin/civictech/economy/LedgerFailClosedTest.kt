package civictech.economy

import civictech.cell.BudgetClaim
import civictech.cell.BudgetOutcome
import civictech.cell.ClaimClass
import civictech.cell.DenialReason
import civictech.cell.link.PeerId
import civictech.cell.link.PeerStamp
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * BS-20 (ledger half), `[ECO1-CHG-10]`/`[ECO1-CHG-11]`, `kwhw6-D7`: a failing ledger refuses
 * with `LEDGER_FAILURE` and never throws; a refusal consumes no budget.
 */
class LedgerFailClosedTest {

    private var now = 0L
    private val stamp = PeerStamp(PeerId("p"))

    private val policy = EconomicPolicy.placeholder().copy(
        unvouchedBootstrap = mapOf(ClaimClass.Spawn to 1L),
    ).applied()

    @Test
    fun `an exception inside charge is refused as LEDGER_FAILURE, not thrown`() {
        val ledger = TokenBucketLedger(policy, { throw IllegalStateException("clock broken") }, "broken-scope")

        val refused = ledger.charge(BudgetClaim(stamp, ClaimClass.Spawn)).shouldBeInstanceOf<BudgetOutcome.Refused>()
        refused.reason shouldBe DenialReason.LEDGER_FAILURE
        refused.scope shouldBe "broken-scope"
        refused.shortfall.shouldBeNull()
        refused.detail!! shouldContain "clock broken"

        val snap = ledger.snapshot()
        snap.denied[ClaimClass.Spawn]!![DenialReason.LEDGER_FAILURE] shouldBe 1
        snap.bucketCount shouldBe 0
    }

    @Test
    fun `a principal at zero balance is refused and the refusal costs nothing`() {
        val ledger = TokenBucketLedger(policy, { now }, "scope")
        ledger.charge(BudgetClaim(stamp, ClaimClass.Spawn)).shouldBeInstanceOf<BudgetOutcome.Admitted>()
        val before = ledger.snapshot().bucket(stamp.id, ClaimClass.Spawn)!!
        before.balance shouldBe 0

        val refused = ledger.charge(BudgetClaim(stamp, ClaimClass.Spawn)).shouldBeInstanceOf<BudgetOutcome.Refused>()
        refused.reason shouldBe DenialReason.BUDGET_EXHAUSTED
        refused.shortfall shouldBe 1

        val after = ledger.snapshot()
        after.denied[ClaimClass.Spawn]!![DenialReason.BUDGET_EXHAUSTED] shouldBe 1
        after.bucket(stamp.id, ClaimClass.Spawn) shouldBe before
    }
}
