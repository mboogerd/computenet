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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * BS-03, `[ECO1-BUD-06]`/`[ECO1-BUD-07]`, `kwhw6-D3`: refill by whole intervals of the
 * injected clock, capped at `capacity - held`, remainder carried; plus the concurrency
 * criterion (8 threads x 1000 unit claims against capacity 4000).
 */
class TokenBucketRefillTest {

    private var now = 0L
    private val peer = PeerId("p")
    private val stamp = PeerStamp(peer)

    /** Spawn: capacity 10, refill 2 per 100 ns, price 2, unvouched bootstrap 10 (a full bucket). */
    private fun ledger(): TokenBucketLedger {
        val policy = EconomicPolicy.placeholder().copy(
            prices = mapOf(ClaimClass.Spawn to 2L),
            capacities = mapOf(ClaimClass.Spawn to 10L),
            refill = mapOf(ClaimClass.Spawn to EconomicPolicy.Refill(tokensPerInterval = 2, intervalNanos = 100)),
            unvouchedBootstrap = mapOf(ClaimClass.Spawn to 10L),
        ).applied()
        return TokenBucketLedger(policy, { now }, "test-scope")
    }

    private fun TokenBucketLedger.spawn(hold: Boolean = false) = charge(BudgetClaim(stamp, ClaimClass.Spawn, hold = hold))

    private fun TokenBucketLedger.balance() = snapshot().bucket(peer, ClaimClass.Spawn)!!.balance

    private fun TokenBucketLedger.drain() {
        repeat(5) { spawn().shouldBeInstanceOf<BudgetOutcome.Admitted>() }
        balance() shouldBe 0
        val refused = spawn().shouldBeInstanceOf<BudgetOutcome.Refused>()
        refused.reason shouldBe DenialReason.BUDGET_EXHAUSTED
        refused.shortfall shouldBe 2
    }

    @Test
    fun `one interval refills exactly tokensPerInterval`() {
        val l = ledger()
        l.drain()
        now += 100
        l.spawn().shouldBeInstanceOf<BudgetOutcome.Admitted>()
        l.balance() shouldBe 0 // 2 refilled, 2 spent
        l.spawn().shouldBeInstanceOf<BudgetOutcome.Refused>()
    }

    @Test
    fun `n one-interval advances equal one n-interval advance`() {
        val stepped = ledger().apply { drain() }
        repeat(3) {
            now += 100
            stepped.spawn().shouldBeInstanceOf<BudgetOutcome.Admitted>()
        }
        stepped.balance() shouldBe 0

        now = 0
        val jumped = ledger().apply { drain() }
        now += 300
        repeat(3) { jumped.spawn().shouldBeInstanceOf<BudgetOutcome.Admitted>() }
        jumped.balance() shouldBe 0
        jumped.snapshot().admitted shouldBe stepped.snapshot().admitted
    }

    @Test
    fun `partial-interval remainder is carried, not lost`() {
        val stepped = ledger().apply { drain() }
        now += 150 // one whole interval, 50 ns carried
        stepped.spawn().shouldBeInstanceOf<BudgetOutcome.Admitted>()
        now += 150 // 200 ns since the last whole interval: two more
        stepped.spawn().shouldBeInstanceOf<BudgetOutcome.Admitted>()
        stepped.balance() shouldBe 2

        now = 0
        val jumped = ledger().apply { drain() }
        now += 300
        repeat(2) { jumped.spawn().shouldBeInstanceOf<BudgetOutcome.Admitted>() }
        jumped.balance() shouldBe stepped.balance()
    }

    @Test
    fun `a long idle refills to capacity, never beyond`() {
        val l = ledger()
        l.drain()
        now += 10_000 // 100 intervals would be 200 tokens
        l.spawn().shouldBeInstanceOf<BudgetOutcome.Admitted>()
        l.balance() shouldBe 8 // refilled to 10, spent 2
    }

    @Test
    fun `undo of a debit at capacity does not exceed capacity`() {
        val l = ledger()
        val first = l.spawn().shouldBeInstanceOf<BudgetOutcome.Admitted>()
        now += 100
        val second = l.spawn().shouldBeInstanceOf<BudgetOutcome.Admitted>() // refilled to 10, then 8
        l.balance() shouldBe 8
        first.undo()
        l.balance() shouldBe 10
        second.undo()
        l.balance() shouldBe 10
        second.undo() // once-only
        l.balance() shouldBe 10
    }

    @Test
    fun `held tokens cap refill at capacity minus held`() {
        val l = ledger()
        val h1 = l.spawn(hold = true).shouldBeInstanceOf<BudgetOutcome.Admitted>()
        l.spawn(hold = true).shouldBeInstanceOf<BudgetOutcome.Admitted>()
        l.snapshot().bucket(peer, ClaimClass.Spawn)!!.held shouldBe 4
        repeat(3) { l.spawn().shouldBeInstanceOf<BudgetOutcome.Admitted>() }
        l.balance() shouldBe 0

        now += 10_000
        l.spawn().shouldBeInstanceOf<BudgetOutcome.Admitted>()
        l.balance() shouldBe 4 // refilled to 10 - 4 = 6, spent 2
        l.snapshot().bucket(peer, ClaimClass.Spawn)!!.held shouldBe 4

        h1.undo()
        val view = l.snapshot().bucket(peer, ClaimClass.Spawn)!!
        view.held shouldBe 2
        view.balance shouldBe 6
        h1.undo() // once-only
        l.snapshot().bucket(peer, ClaimClass.Spawn)!!.held shouldBe 2
    }

    @Test
    fun `concurrent unit claims admit exactly capacity and undo restores it`() {
        val policy = EconomicPolicy.placeholder().copy(
            prices = mapOf(ClaimClass.Spawn to 1L),
            capacities = mapOf(ClaimClass.Spawn to 4000L),
            refill = mapOf(ClaimClass.Spawn to EconomicPolicy.Refill(1, Long.MAX_VALUE)),
            unvouchedBootstrap = mapOf(ClaimClass.Spawn to 4000L),
        ).applied()
        val l = TokenBucketLedger(policy, { now }, "concurrent")
        val undos = ConcurrentLinkedQueue<() -> Unit>()
        val refused = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val futures = (1..8).map {
                pool.submit {
                    start.await()
                    repeat(1000) {
                        when (val o = l.charge(BudgetClaim(stamp, ClaimClass.Spawn))) {
                            is BudgetOutcome.Admitted -> undos += o.undo
                            is BudgetOutcome.Refused -> refused.incrementAndGet()
                        }
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }

            undos.size shouldBe 4000
            refused.get() shouldBe 4000
            l.balance() shouldBe 0

            val all = undos.toList()
            (0 until 8).map { t -> pool.submit { all.filterIndexed { i, _ -> i % 8 == t }.forEach { it() } } }
                .forEach { it.get(60, TimeUnit.SECONDS) }
            l.balance() shouldBe 4000
        } finally {
            pool.shutdownNow()
        }
    }
}
