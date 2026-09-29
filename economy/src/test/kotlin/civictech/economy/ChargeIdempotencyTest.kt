package civictech.economy

import civictech.cell.BudgetClaim
import civictech.cell.BudgetOutcome
import civictech.cell.ClaimClass
import civictech.cell.DenialReason
import civictech.cell.link.PeerId
import civictech.cell.link.PeerStamp
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * `[ECO1-CHG-08]` (ledger half), `kwhw6-D8`: a claim whose non-null key is among the bucket's
 * last `retention.recentKeys` admitted keys is admitted again with a no-op `undo` and no second
 * debit; `null` keys and refused claims are never deduplicated.
 */
class ChargeIdempotencyTest {

    private var now = 0L

    /** Spawn price 1, capacity 10, refill 1 per 1e9 ns, unvouched bootstrap 5; a window of 2 keys. */
    private val ledger = TokenBucketLedger(
        EconomicPolicy.placeholder().copy(
            unvouchedBootstrap = mapOf(ClaimClass.Spawn to 5L),
            retention = EconomicPolicy.Retention(idleNanos = 1_000, maxBuckets = 3, recentKeys = 2),
        ).applied(),
        { now },
        "idem-scope",
    )

    private fun spawn(peer: String, key: Any?) = ledger.charge(BudgetClaim(PeerStamp(PeerId(peer)), ClaimClass.Spawn, key = key))

    private fun balance(peer: String) = ledger.snapshot().bucket(PeerId(peer), ClaimClass.Spawn)!!.balance

    @Test
    fun `a replayed key within the window is admitted without a second debit`() {
        spawn("p", "k1").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        balance("p") shouldBe 4

        val replay = spawn("p", "k1").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        balance("p") shouldBe 4
        ledger.snapshot().admitted[ClaimClass.Spawn] shouldBe 2

        // The replay's undo is a no-op: it must not refund the original debit.
        replay.undo()
        replay.undo()
        balance("p") shouldBe 4

        spawn("p", "k2").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        spawn("p", "k3").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        balance("p") shouldBe 2

        // "k1" has left a window of 2 ("k2", "k3"): it is a fresh claim again.
        spawn("p", "k1").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        balance("p") shouldBe 1
    }

    @Test
    fun `null keys never deduplicate`() {
        repeat(4) { spawn("q", null).shouldBeInstanceOf<BudgetOutcome.Admitted>() }
        balance("q") shouldBe 1
    }

    @Test
    fun `a refused claim records no key so its retry is re-evaluated`() {
        repeat(5) { spawn("r", null).shouldBeInstanceOf<BudgetOutcome.Admitted>() }
        balance("r") shouldBe 0

        val refused = spawn("r", "k9").shouldBeInstanceOf<BudgetOutcome.Refused>()
        refused.reason shouldBe DenialReason.BUDGET_EXHAUSTED

        now += 1_000_000_000 // one refill interval: +1 token
        spawn("r", "k9").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        balance("r") shouldBe 0 // admitted AND debited: the refusal left nothing to replay
    }

    @Test
    fun `a replayed hold neither holds again nor releases the original on undo`() {
        val l = TokenBucketLedger(
            EconomicPolicy.placeholder().copy(
                unvouchedBootstrap = mapOf(ClaimClass.Retention to 5L),
                retention = EconomicPolicy.Retention(idleNanos = 1_000, maxBuckets = 3, recentKeys = 2),
            ).applied(),
            { now },
            "idem-scope",
        )
        val claim = BudgetClaim(PeerStamp(PeerId("h")), ClaimClass.Retention, key = "h1", hold = true)
        l.charge(claim).shouldBeInstanceOf<BudgetOutcome.Admitted>()
        l.charge(claim).shouldBeInstanceOf<BudgetOutcome.Admitted>().undo()
        l.snapshot().bucket(PeerId("h"), ClaimClass.Retention)!!.let {
            it.balance shouldBe 4
            it.held shouldBe 1
        }
    }

    @Test
    fun `a zero-size window disables deduplication`() {
        val l = TokenBucketLedger(
            EconomicPolicy.placeholder().copy(
                unvouchedBootstrap = mapOf(ClaimClass.Spawn to 5L),
                retention = EconomicPolicy.Retention(idleNanos = 1_000, maxBuckets = 3, recentKeys = 0),
            ).applied(),
            { now },
            "idem-scope",
        )
        repeat(3) { l.charge(BudgetClaim(PeerStamp(PeerId("z")), ClaimClass.Spawn, key = "same")) }
        l.snapshot().bucket(PeerId("z"), ClaimClass.Spawn)!!.balance shouldBe 2
    }
}
