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

        // The replay's undo is a no-op: it must not refund the original debit, and (sb9v1) it
        // must not forget the key either — the original admission's dedup survives it.
        replay.undo()
        replay.undo()
        balance("p") shouldBe 4
        spawn("p", "k1").shouldBeInstanceOf<BudgetOutcome.Admitted>()
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
    fun `sb9v1 - undoing a real debit forgets its key, so a retry with the same key is charged again`() {
        val admitted = spawn("s", "k1").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        balance("s") shouldBe 4

        admitted.undo()
        balance("s") shouldBe 5

        // Without the fix this second charge would hit the replay branch (key "k1" still in the
        // window) and be admitted free, at balance 5. It must debit again.
        spawn("s", "k1").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        balance("s") shouldBe 4
        ledger.snapshot().admitted[ClaimClass.Spawn] shouldBe 2
    }

    @Test
    fun `8s74x - undoing a zero-price admission forgets its key, so a retry is a fresh admission`() {
        val free = TokenBucketLedger(
            EconomicPolicy.placeholder().copy(
                prices = EconomicPolicy.placeholder().prices + (ClaimClass.Spawn to 0L),
                unvouchedBootstrap = mapOf(ClaimClass.Spawn to 5L),
                retention = EconomicPolicy.Retention(idleNanos = 1_000, maxBuckets = 3, recentKeys = 2),
            ).applied(),
            { now },
            "idem-scope-free",
        )
        fun claim() = BudgetClaim(PeerStamp(PeerId("z")), ClaimClass.Spawn, key = "k1")

        val first = free.charge(claim()).shouldBeInstanceOf<BudgetOutcome.Admitted>()
        // Replay while the key is in the window: admitted, but a no-op undo.
        free.charge(claim()).shouldBeInstanceOf<BudgetOutcome.Admitted>().undo()
        free.charge(claim()).shouldBeInstanceOf<BudgetOutcome.Admitted>()

        // The real admission's undo takes the monitor and forgets the key.
        first.undo()
        val retry = free.charge(claim()).shouldBeInstanceOf<BudgetOutcome.Admitted>()
        // A fresh admission has a real undo: undoing it forgets the key again, so the next
        // charge is once more a recorded admission rather than a replay (no-op undo).
        retry.undo()
        free.charge(claim()).shouldBeInstanceOf<BudgetOutcome.Admitted>()
        free.snapshot().admitted[ClaimClass.Spawn] shouldBe 5
    }

    @Test
    fun `sb9v1 - undoing a real hold's release forgets its key, so a retry with the same key holds again`() {
        val l = TokenBucketLedger(
            EconomicPolicy.placeholder().copy(
                unvouchedBootstrap = mapOf(ClaimClass.Retention to 5L),
                retention = EconomicPolicy.Retention(idleNanos = 1_000, maxBuckets = 3, recentKeys = 2),
            ).applied(),
            { now },
            "idem-scope",
        )
        val claim = BudgetClaim(PeerStamp(PeerId("h2")), ClaimClass.Retention, key = "hk1", hold = true)

        val admitted = l.charge(claim).shouldBeInstanceOf<BudgetOutcome.Admitted>()
        l.snapshot().bucket(PeerId("h2"), ClaimClass.Retention)!!.let {
            it.balance shouldBe 4
            it.held shouldBe 1
        }

        admitted.undo()
        l.snapshot().bucket(PeerId("h2"), ClaimClass.Retention)!!.let {
            it.balance shouldBe 5
            it.held shouldBe 0
        }

        // Same key, retried after undo: must hold again, not replay.
        l.charge(claim).shouldBeInstanceOf<BudgetOutcome.Admitted>()
        l.snapshot().bucket(PeerId("h2"), ClaimClass.Retention)!!.let {
            it.balance shouldBe 4
            it.held shouldBe 1
        }
        l.snapshot().admitted[ClaimClass.Retention] shouldBe 2
    }

    @Test
    fun `computenet-chgam - a stale undo does not forget a newer admission's reused key`() {
        val a1 = spawn("t", "k1").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        balance("t") shouldBe 4

        // Age "k1" out of the window (size 2: after these, the window holds "k2", "k3").
        spawn("t", "k2").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        spawn("t", "k3").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        balance("t") shouldBe 2

        // A2: a genuine fresh admission reusing key "k1", now back in the window.
        spawn("t", "k1").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        balance("t") shouldBe 1

        // A1's undo is stale: its own window entry is long gone, reused by A2. It must still
        // refund A1's own debit, but it must NOT forget "k1" out from under A2.
        a1.undo()
        balance("t") shouldBe 2

        // A replay of A2's key must still be recognised as a replay, not charged again.
        spawn("t", "k1").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        balance("t") shouldBe 2
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
