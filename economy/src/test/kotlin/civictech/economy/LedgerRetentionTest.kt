package civictech.economy

import civictech.cell.BudgetClaim
import civictech.cell.BudgetOutcome
import civictech.cell.ClaimClass
import civictech.cell.DenialReason
import civictech.cell.link.PeerId
import civictech.cell.link.PeerStamp
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * BS-22, `[ECO1-BUD-11]`/`[ECO1-BUD-12]`, `kwhw6-D9`: ledger state is bounded. Only buckets at
 * or above their bootstrap with nothing held are evictable; `sweep()` removes the idle ones;
 * `bucketCount` never exceeds `retention.maxBuckets`, and a full ledger with nothing
 * evictable refuses a new principal fail-closed.
 */
class LedgerRetentionTest {

    private var now = 0L

    /** Spawn and Retention price 1, capacity 10, refill 1 per 1e9 ns, unvouched bootstrap 5. */
    private fun ledger(maxBuckets: Int = 3, clock: () -> Long = { now }) = TokenBucketLedger(
        EconomicPolicy.placeholder().copy(
            unvouchedBootstrap = mapOf(ClaimClass.Spawn to 5L, ClaimClass.Retention to 5L),
            retention = EconomicPolicy.Retention(idleNanos = 1_000, maxBuckets = maxBuckets, recentKeys = 2),
        ).applied(),
        clock,
        "retention-scope",
    )

    private fun TokenBucketLedger.spawn(peer: String) = charge(BudgetClaim(PeerStamp(PeerId(peer)), ClaimClass.Spawn))

    /** A charge immediately undone: the bucket exists, is back at bootstrap, and is eligible. */
    private fun TokenBucketLedger.touch(peer: String) = spawn(peer).shouldBeInstanceOf<BudgetOutcome.Admitted>().undo()

    private fun TokenBucketLedger.drain(peer: String) {
        repeat(5) { spawn(peer).shouldBeInstanceOf<BudgetOutcome.Admitted>() }
        snapshot().bucket(PeerId(peer), ClaimClass.Spawn)!!.balance shouldBe 0
    }

    @Test
    fun `(a) a bucket below its bootstrap is never swept and stays exhausted`() {
        val l = ledger()
        l.drain("P")
        now += 2_000
        l.sweep() shouldBe 0
        l.snapshot().bucketCount shouldBe 1
        l.spawn("P").shouldBeInstanceOf<BudgetOutcome.Refused>().reason shouldBe DenialReason.BUDGET_EXHAUSTED
    }

    @Test
    fun `(a2) a drained bucket that has refilled back to its bootstrap is swept without being touched`() {
        val l = ledger()
        l.drain("P")
        now += 5_000_000_000 // five refill intervals of 1 token: 0 -> 5 == bootstrap, never charged since
        l.sweep() shouldBe 1
        l.snapshot().bucketCount shouldBe 0
    }

    @Test
    fun `(e2) drained buckets that have refilled to bootstrap do not pin a full ledger`() {
        // An idle bound far above the refill time, so the creation-time sweep frees nothing and
        // only the oldest-eligible eviction can make room.
        val l = TokenBucketLedger(
            EconomicPolicy.placeholder().copy(
                unvouchedBootstrap = mapOf(ClaimClass.Spawn to 5L),
                retention = EconomicPolicy.Retention(idleNanos = 1_000_000_000_000, maxBuckets = 3, recentKeys = 2),
            ).applied(),
            { now },
            "retention-scope",
        )
        l.drain("A")
        l.drain("B")
        l.drain("C")
        now += 5_000_000_000 // each refills 0 -> 5 == bootstrap, so each is eligible again
        l.spawn("D").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        l.snapshot().bucketCount shouldBe 3
    }

    @Test
    fun `(b) an idle bucket at its bootstrap is swept`() {
        val l = ledger()
        l.touch("Q")
        l.snapshot().bucket(PeerId("Q"), ClaimClass.Spawn)!!.balance shouldBe 5
        now += 500
        l.sweep() shouldBe 0 // eligible but not yet idle beyond 1_000
        now += 1_500
        l.sweep() shouldBe 1
        l.snapshot().bucketCount shouldBe 0
    }

    @Test
    fun `(c) a bucket with a hold outstanding is not swept until the hold is released`() {
        val l = ledger()
        val hold = l.charge(BudgetClaim(PeerStamp(PeerId("R")), ClaimClass.Retention, hold = true))
            .shouldBeInstanceOf<BudgetOutcome.Admitted>()
        now += 2_000
        l.sweep() shouldBe 0
        l.snapshot().bucket(PeerId("R"), ClaimClass.Retention).shouldNotBeNull().held shouldBe 1

        hold.undo()
        l.sweep() shouldBe 1
        l.snapshot().bucketCount shouldBe 0
    }

    @Test
    fun `(d) a full ledger evicts the least-recently-accessed eligible bucket`() {
        val l = ledger()
        l.touch("A"); now += 10
        l.touch("B"); now += 10
        l.touch("C"); now += 10 // none idle beyond 1_000: the sweep frees nothing
        l.touch("D")
        val snap = l.snapshot()
        snap.bucketCount shouldBe 3
        snap.bucket(PeerId("A"), ClaimClass.Spawn).shouldBeNull()
        snap.bucket(PeerId("B"), ClaimClass.Spawn).shouldNotBeNull()
        snap.bucket(PeerId("D"), ClaimClass.Spawn).shouldNotBeNull()
    }

    @Test
    fun `(e) a full ledger with nothing eligible refuses a new principal fail-closed`() {
        val l = ledger()
        l.drain("A")
        l.drain("B")
        l.drain("C")
        now += 2_000

        val refused = l.spawn("D").shouldBeInstanceOf<BudgetOutcome.Refused>()
        refused.reason shouldBe DenialReason.LEDGER_FAILURE
        refused.detail shouldBe "ledger full"
        refused.detail shouldBe TokenBucketLedger.LEDGER_FULL_DETAIL
        refused.shortfall.shouldBeNull()
        refused.scope shouldBe "retention-scope"
        val snap = l.snapshot()
        snap.bucketCount shouldBe 3
        snap.bucket(PeerId("D"), ClaimClass.Spawn).shouldBeNull()
        snap.denied[ClaimClass.Spawn]!![DenialReason.LEDGER_FAILURE] shouldBe 1
        // The drained principals are still there and still exhausted.
        l.spawn("A").shouldBeInstanceOf<BudgetOutcome.Refused>().reason shouldBe DenialReason.BUDGET_EXHAUSTED
    }

    @Test
    fun `(g) sb9v1 - a debit undone after its bucket was evicted refunds into the discarded bucket and is lost`() {
        val l = ledger()
        val admitted = l.spawn("E").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        l.snapshot().bucket(PeerId("E"), ClaimClass.Spawn)!!.balance shouldBe 4

        // Refill back to bootstrap (eligible) without ever calling undo, then sweep it away.
        now += 5_000_000_000 // five refill intervals of 1 token: 4 -> 9 (>= bootstrap 5, eligible)
        l.sweep() shouldBe 1
        l.snapshot().bucketCount shouldBe 0

        // The original debit's undo now runs against the discarded Bucket object: fail-safe
        // (no budget is granted), but the refund itself is lost rather than reaching a live
        // bucket, per the "Undo forgets the key" / "Bounded state" KDoc.
        admitted.undo()

        // The next charge for "E" creates a fresh bucket at bootstrap, unaffected by the lost refund.
        l.spawn("E").shouldBeInstanceOf<BudgetOutcome.Admitted>()
        l.snapshot().bucket(PeerId("E"), ClaimClass.Spawn)!!.balance shouldBe 4
    }

    @Test
    fun `(f) bucket count stays within maxBuckets however many principals are ever charged`() {
        val l = ledger()
        for (i in 0 until 1_000) {
            now += 1
            // Each principal is left at its bootstrap (evictable), so every new one is admitted
            // by displacing the oldest eligible bucket rather than growing the map.
            l.touch("p$i")
            l.snapshot().bucketCount shouldBeLessThanOrEqual 3
        }
        l.snapshot().admitted[ClaimClass.Spawn] shouldBe 1_000
    }

    /**
     * 16 simultaneous first charges (distinct principals, each undone so its bucket is
     * eligible) under `maxBuckets = 4`, repeated over 50 fresh ledgers. The post-condition is
     * exact: only creation grows the map, so a creation that slipped past the size check
     * leaves a fifth bucket that nothing removes. No mid-flight watcher: `snapshot()` iterates
     * the map weakly-consistently (see [LedgerSnapshot]), so while one creator evicts A and
     * inserts E a concurrent snapshot can list both and report 5 with the map never above 4.
     */
    @Test
    fun `concurrent first charges never exceed maxBuckets`() {
        repeat(50) { trial ->
            val l = ledger(maxBuckets = 4, clock = { 0L })
            val pool = Executors.newFixedThreadPool(16)
            val start = CountDownLatch(1)
            val admitted = AtomicInteger()
            val full = AtomicInteger()
            repeat(16) { t ->
                pool.execute {
                    start.await()
                    when (val o = l.spawn("t$trial-$t")) {
                        is BudgetOutcome.Admitted -> { admitted.incrementAndGet(); o.undo() }
                        is BudgetOutcome.Refused -> if (o.detail == "ledger full") full.incrementAndGet()
                    }
                }
            }
            start.countDown()
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS) shouldBe true

            (admitted.get() + full.get()) shouldBe 16
            l.snapshot().bucketCount shouldBe 4
        }
    }
}
