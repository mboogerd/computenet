package civictech.economy.persist

import civictech.cell.BudgetClaim
import civictech.cell.BudgetOutcome
import civictech.cell.ClaimClass
import civictech.cell.DenialReason
import civictech.cell.link.PeerId
import civictech.cell.link.PeerStamp
import civictech.economy.EconomicPolicy
import civictech.economy.TokenBucketLedger
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

/**
 * `66m-D9`: checkpointing is a caller-owned cadence operation. Charges and
 * undos never write; a tick before the cadence is a no-op; an orderly
 * `checkpointTo` always writes. The spy is deliberately in-memory so write
 * counts cannot be confused with filesystem behavior covered by the store
 * tests.
 */
class BudgetCheckpointCadenceTest {

    private var now = 0L

    private class SpyStore(
        private val refusal: CheckpointStoreRefusedException? = null,
    ) : BudgetCheckpointStore {
        var writes = 0
        val states = mutableListOf<LedgerState>()

        override fun write(scope: String, state: LedgerState) {
            writes++
            refusal?.let { throw it }
            states += state
        }

        override fun read(scope: String): CheckpointRead =
            states.lastOrNull()?.let { CheckpointRead.Present(it) } ?: CheckpointRead.Missing

        override fun wallMillis(): Long = 0L
    }

    private fun policy(
        attentionPrice: Long = 1,
        attentionCapacity: Long = 1_000,
        attentionBootstrap: Long = 1_000,
        retention: Boolean = false,
    ) = EconomicPolicy.placeholder().copy(
        prices = buildMap {
            put(ClaimClass.Attention, attentionPrice)
            if (retention) put(ClaimClass.Retention, 1L)
        },
        capacities = buildMap {
            put(ClaimClass.Attention, attentionCapacity)
            if (retention) put(ClaimClass.Retention, 10L)
        },
        refill = buildMap {
            put(ClaimClass.Attention, EconomicPolicy.Refill(1, 1_000))
            if (retention) put(ClaimClass.Retention, EconomicPolicy.Refill(1, 1_000))
        },
        checkpoint = EconomicPolicy.Checkpoint(cadenceNanos = 10_000, stalenessBoundNanos = 60_000_000_000),
        unvouchedBootstrap = buildMap {
            put(ClaimClass.Attention, attentionBootstrap)
            if (retention) put(ClaimClass.Retention, 10L)
        },
        label = "cadence-policy",
    ).applied()

    private fun ledger(
        attentionPrice: Long = 1,
        attentionCapacity: Long = 1_000,
        attentionBootstrap: Long = 1_000,
        retention: Boolean = false,
    ) = TokenBucketLedger(
        policy(attentionPrice, attentionCapacity, attentionBootstrap, retention),
        { now },
        "cadence-scope",
    )

    @Test
    fun `charges and undos never write a checkpoint` () {
        val store = SpyStore()
        val ledger = ledger()
        // Hand the ledger the store once, so a ledger that kept it and wrote per charge
        // would be visible here; without this tick the spy is unreachable from charge.
        ledger.tick(store, now = 0L)!!.sequence shouldBe 1L
        store.writes shouldBe 1
        val admissions = (0 until 1_000).map { key ->
            ledger.charge(
                BudgetClaim(PeerStamp(PeerId("peer")), ClaimClass.Attention, key = key),
            ).shouldBeInstanceOf<BudgetOutcome.Admitted>()
        }

        store.writes shouldBe 1
        admissions.forEach { it.undo() }
        store.writes shouldBe 1
    }

    @Test
    fun `tick follows cadence and orderly checkpoint is unconditional`() {
        val store = SpyStore()
        val ledger = ledger(attentionCapacity = 10, attentionBootstrap = 10)
        ledger.charge(BudgetClaim(PeerStamp(PeerId("peer")), ClaimClass.Attention))
            .shouldBeInstanceOf<BudgetOutcome.Admitted>()

        ledger.tick(store, now = 0L)!!.sequence shouldBe 1L
        store.writes shouldBe 1
        ledger.tick(store, now = 9_999L) shouldBe null
        store.writes shouldBe 1
        ledger.tick(store, now = 10_000L)!!.sequence shouldBe 2L
        store.writes shouldBe 2

        ledger.checkpointTo(store).sequence shouldBe 3L
        store.writes shouldBe 3
        store.states.map { it.sequence } shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `checkpoint records every live bucket with snapshot values`() {
        val store = SpyStore()
        val ledger = ledger(attentionCapacity = 10, attentionBootstrap = 10, retention = true)
        ledger.charge(BudgetClaim(PeerStamp(PeerId("attention-peer")), ClaimClass.Attention))
            .shouldBeInstanceOf<BudgetOutcome.Admitted>()
        ledger.charge(
            BudgetClaim(PeerStamp(PeerId("retention-peer")), ClaimClass.Retention, hold = true),
        ).shouldBeInstanceOf<BudgetOutcome.Admitted>()

        val snapshot = ledger.snapshot()
        val state = ledger.checkpointTo(store)
        state.buckets.size shouldBe 2
        state.buckets.forEach { record ->
            val view = snapshot.bucket(PeerId(record.peer), record.claimClass)!!
            record.balance shouldBe view.balance
            record.heldTotal shouldBe view.held
            record.bootstrapLevel shouldBe view.bootstrapLevel
        }
    }

    @Test
    fun `store refusal from tick propagates without changing charge behavior`() {
        val refusal = CheckpointStoreRefusedException(
            path = Path.of("/unwritable-checkpoint"),
            reason = CheckpointStoreRefusal.UNWRITABLE,
            detail = "test refusal",
        )
        val store = SpyStore(refusal)
        val ledger = ledger(attentionCapacity = 1, attentionBootstrap = 1)
        val claim = BudgetClaim(PeerStamp(PeerId("peer")), ClaimClass.Attention)

        ledger.charge(claim).shouldBeInstanceOf<BudgetOutcome.Admitted>()
        assertThrows<CheckpointStoreRefusedException> { ledger.tick(store, now = 0L) }
            .reason shouldBe CheckpointStoreRefusal.UNWRITABLE
        ledger.charge(claim).shouldBeInstanceOf<BudgetOutcome.Refused>().reason shouldBe
            DenialReason.BUDGET_EXHAUSTED
    }
}
