package civictech.economy

import civictech.cell.BudgetClaim
import civictech.cell.BudgetOutcome
import civictech.cell.ClaimClass
import civictech.cell.DenialReason
import civictech.cell.link.AuthLevel.Authenticated
import civictech.cell.link.IssuerId
import civictech.cell.link.PeerId
import civictech.cell.link.PeerStamp
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * `66m-D2`, `66m-D7`, `kwhw6-D5`, `[ECO1-DEN-11]`: holds on buckets one issuer row
 * bootstrapped are capped in aggregate; the refusal names the aggregate, never another
 * principal's balance, and releasing a hold frees room under the cap.
 *
 * The cap is 10, not the 15 the task description's example gives: with three holds of 5 a
 * cap of 15 admits the third (10 + 5 is not over 15), and the prescribed shortfall of 5 =
 * `price - (cap - sum)` holds only when the sum already equals the cap — i.e. cap 10.
 */
class AggregateHoldCapTest {

    private var now = 0L

    private val ledger = TokenBucketLedger(
        EconomicPolicy.placeholder().copy(
            prices = EconomicPolicy.placeholder().prices + (ClaimClass.Retention to 5L),
            issuers = mapOf(
                "A" to EconomicPolicy.IssuerBudget(bootstrap = mapOf(ClaimClass.Retention to 10L), aggregateHoldCap = 10L),
            ),
        ).applied(),
        { now },
        "hold-scope",
    )

    private fun stamp(name: String) = PeerStamp(PeerId(name), Authenticated, IssuerId("A"))

    private fun hold(name: String) = ledger.charge(BudgetClaim(stamp(name), ClaimClass.Retention, hold = true))

    @Test
    fun `third hold over the issuer aggregate is refused and undo frees room`() {
        val first = hold("p1").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        hold("p2").shouldBeInstanceOf<BudgetOutcome.Admitted>()

        val refused = hold("p3").shouldBeInstanceOf<BudgetOutcome.Refused>()
        refused.reason shouldBe DenialReason.BUDGET_EXHAUSTED
        refused.shortfall shouldBe 5
        refused.detail shouldBe "issuer aggregate hold cap"
        refused.scope shouldBe "hold-scope"
        // [ECO1-DEN-11]: no other principal named, no other balance (5 and 5) quoted.
        refused.detail!!.let {
            it shouldNotContain "p1"
            it shouldNotContain "p2"
            it shouldNotContain "5"
        }
        ledger.snapshot().bucket(PeerId("p3"), ClaimClass.Retention)!!.let {
            it.balance shouldBe 10 // untouched by the refusal
            it.held shouldBe 0
        }
        ledger.snapshot().bucket(PeerId("p1"), ClaimClass.Retention)!!.let {
            it.balance shouldBe 5
            it.held shouldBe 5
        }

        first.undo()
        ledger.snapshot().bucket(PeerId("p1"), ClaimClass.Retention)!!.let {
            it.balance shouldBe 10
            it.held shouldBe 0
        }
        hold("p3").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        ledger.snapshot().bucket(PeerId("p3"), ClaimClass.Retention)!!.held shouldBe 5
    }

    @Test
    fun `debits do not count toward the aggregate hold cap`() {
        repeat(2) { ledger.charge(BudgetClaim(stamp("d$it"), ClaimClass.Retention)).shouldBeInstanceOf<BudgetOutcome.Admitted>() }
        hold("p1").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        hold("p2").shouldBeInstanceOf<BudgetOutcome.Admitted>()
    }
}
