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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * BS-15, `[ECO1-PAR-08]`: every unusable checkpoint records why it fell back,
 * and the next first charge receives bootstrap rather than capacity.
 */
class BudgetCheckpointFallbackTest {

    private var monotonicNanos = 0L
    private var wallMillis = 10_000L
    private val peer = PeerId("alice")
    private val stamp = PeerStamp(peer)

    private fun policy(label: String = "policy-a", checkpoint: Boolean = true) =
        EconomicPolicy.placeholder().copy(
            prices = mapOf(ClaimClass.Attention to 1L),
            capacities = mapOf(ClaimClass.Attention to 10L),
            refill = mapOf(ClaimClass.Attention to EconomicPolicy.Refill(1, 1_000_000_000)),
            checkpoint = if (checkpoint) {
                EconomicPolicy.Checkpoint(cadenceNanos = 1_000, stalenessBoundNanos = 60_000_000_000)
            } else {
                null
            },
            unvouchedBootstrap = mapOf(ClaimClass.Attention to 3L),
            label = label,
        ).applied()

    private fun ledger(label: String = "policy-a", checkpoint: Boolean = true) =
        TokenBucketLedger(policy(label, checkpoint), { monotonicNanos }, "scope")

    private fun store(directory: Path) = FileBudgetCheckpointStore(directory) { wallMillis }

    private fun TokenBucketLedger.attention() = charge(BudgetClaim(stamp, ClaimClass.Attention))

    private fun TokenBucketLedger.spendBootstrap() {
        repeat(3) { attention().shouldBeInstanceOf<BudgetOutcome.Admitted>() }
        attention().shouldBeInstanceOf<BudgetOutcome.Refused>().reason shouldBe DenialReason.BUDGET_EXHAUSTED
    }

    private fun assertBootstrapFallback(ledger: TokenBucketLedger) {
        repeat(3) { ledger.attention().shouldBeInstanceOf<BudgetOutcome.Admitted>() }
        ledger.attention().shouldBeInstanceOf<BudgetOutcome.Refused>().reason shouldBe
            DenialReason.BUDGET_EXHAUSTED
        ledger.snapshot().bucket(peer, ClaimClass.Attention)!!.bootstrapLevel shouldBe 3
    }

    private fun checkpointFile(directory: Path): Path =
        Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }.toList().single()
        }

    @Test
    fun `a fresh ledger restores a spent checkpoint and does not refill`(@TempDir directory: Path) {
        val store = store(directory)
        ledger().apply {
            spendBootstrap()
            checkpointTo(store)
        }

        val restored = ledger()
        restored.restoreFrom(store) shouldBe RestoreOutcome.Restored(sequence = 1, buckets = 1)
        restored.attention().shouldBeInstanceOf<BudgetOutcome.Refused>().reason shouldBe
            DenialReason.BUDGET_EXHAUSTED
    }

    @Test
    fun `missing checkpoint falls back to bootstrap`(@TempDir directory: Path) {
        val restored = ledger()

        restored.restoreFrom(store(directory)) shouldBe
            RestoreOutcome.Fallback(FallbackReason.MISSING, detail = null)

        assertBootstrapFallback(restored)
    }

    @Test
    fun `unreadable checkpoint falls back to bootstrap`(@TempDir directory: Path) {
        val store = store(directory)
        ledger().checkpointTo(store)
        Files.writeString(checkpointFile(directory), "{")
        val restored = ledger()

        val outcome = restored.restoreFrom(store).shouldBeInstanceOf<RestoreOutcome.Fallback>()
        outcome.reason shouldBe FallbackReason.UNREADABLE

        assertBootstrapFallback(restored)
    }

    @Test
    fun `checkpoint for another policy falls back to bootstrap`(@TempDir directory: Path) {
        val store = store(directory)
        ledger(label = "other-policy").checkpointTo(store)
        val restored = ledger()

        val outcome = restored.restoreFrom(store).shouldBeInstanceOf<RestoreOutcome.Fallback>()
        outcome.reason shouldBe FallbackReason.POLICY_MISMATCH

        assertBootstrapFallback(restored)
    }

    @Test
    fun `checkpoint older than the staleness bound falls back to bootstrap`(@TempDir directory: Path) {
        val store = store(directory)
        ledger().checkpointTo(store)
        wallMillis += 60_000_000_000 / 1_000_000 + 1
        val restored = ledger()

        val outcome = restored.restoreFrom(store).shouldBeInstanceOf<RestoreOutcome.Fallback>()
        outcome.reason shouldBe FallbackReason.STALE

        assertBootstrapFallback(restored)
    }

    @Test
    fun `checkpoint exactly at the staleness bound restores`(@TempDir directory: Path) {
        val store = store(directory)
        ledger().apply {
            spendBootstrap()
            checkpointTo(store)
        }
        wallMillis += 60_000_000_000 / 1_000_000
        val restored = ledger()

        restored.restoreFrom(store) shouldBe RestoreOutcome.Restored(sequence = 1, buckets = 1)
        restored.attention().shouldBeInstanceOf<BudgetOutcome.Refused>().reason shouldBe
            DenialReason.BUDGET_EXHAUSTED
    }

    @Test
    fun `restore after any charge is rejected`(@TempDir directory: Path) {
        val charged = ledger()
        charged.attention().shouldBeInstanceOf<BudgetOutcome.Admitted>()

        assertThrows<IllegalStateException> { charged.restoreFrom(store(directory)) }
    }

    @Test
    fun `restore and checkpoint require checkpoint policy`(@TempDir directory: Path) {
        val ledger = ledger(checkpoint = false)
        val store = store(directory)

        assertThrows<IllegalStateException> { ledger.restoreFrom(store) }
        assertThrows<IllegalStateException> { ledger.checkpointTo(store) }
    }
}
