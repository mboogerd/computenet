package civictech.cell.host

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.durability.BatchedFileJournal
import civictech.cell.durability.DurabilityClass
import civictech.cell.durability.FileJournal
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `[KBLK-06]`/`[KBLK-07]`/`[KBLK-08]`: [ManagedHost.durabilityAccounting] gives a
 * deployment the per-[DurabilityClass] spawn counts it needs to refuse a relaxed
 * journal ITSELF — the kernel counts, it never refuses (PN-12's rationale,
 * `ManagedHost.kt:388-397` region, applies unchanged; `computenet-t6b.2-D3`).
 *
 * A stub [Journal] declaring [DurabilityClass.BATCHED] stands in for
 * `BatchedFileJournal` (sibling task `computenet-t6b.2.2`; not a dependency here).
 */
class DurabilityAccountingTest {

    /** A minimal [Journal] declaring [DurabilityClass.BATCHED], instrumented to prove BS-08. */
    private class StubBatchedJournal : Journal {
        override val durability: DurabilityClass = DurabilityClass.BATCHED
        var appendCount = 0
            private set
        override fun append(record: ByteArray) {
            appendCount++
        }
        override fun replay(): List<ByteArray> = emptyList()
        override fun reset(records: List<ByteArray>) = Unit
    }

    @Test
    fun `BS-01 - per-class counts are correct and a deployment's own refusal-style assertion fails naming class and count`() {
        val batched = StubBatchedJournal()
        val inMemory = InMemoryJournal()

        val refA = CellRef(UUID.randomUUID()) // -> BATCHED
        val refB = CellRef(UUID.randomUUID()) // -> IN_MEMORY
        val refC = CellRef(UUID.randomUUID()) // -> null (volatile)

        val selector: (CellRef) -> Journal? = {
            when (it) {
                refA -> batched
                refB -> inMemory
                refC -> null
                else -> null
            }
        }

        val host = ManagedHost(journalFor = selector)
        host.managementInlet.call.spawn(SetCell<String>(refA))
        host.managementInlet.call.spawn(SetCell<String>(refB))
        host.managementInlet.call.spawn(SetCell<String>(refC))

        val acct = host.durabilityAccounting()
        acct.journaledSpawns[DurabilityClass.SYNCHRONOUS] shouldBe 0L
        acct.journaledSpawns[DurabilityClass.BATCHED] shouldBe 1L
        acct.journaledSpawns[DurabilityClass.IN_MEMORY] shouldBe 1L
        // SetCell is DURABLE-manifest (ManifestDriftTest); refC's selector is null -> counted by PN-12
        acct.volatileDurableSpawns shouldBe 1L

        // the deployment-style assertion a caller outside :kernel would write
        val failure = shouldThrow<IllegalStateException> {
            check(
                acct.journaledSpawns[DurabilityClass.BATCHED] == 0L &&
                    acct.journaledSpawns[DurabilityClass.IN_MEMORY] == 0L &&
                    acct.volatileDurableSpawns == 0L
            ) { "relaxed durability in use: BATCHED=${acct.journaledSpawns[DurabilityClass.BATCHED]}" }
        }
        failure.message shouldContain "BATCHED"
        failure.message shouldContain "1"

        // the kernel itself refused nothing, dead-lettered nothing: all three spawns succeeded
        host.supervisionAccounting().deadLetters shouldBe 0L
    }

    @Test
    fun `BS-08 - the per-class count is observable the moment spawn returns, before any append or drive`() {
        val batched = StubBatchedJournal()
        val ref = CellRef(UUID.randomUUID())
        val selector: (CellRef) -> Journal? = { if (it == ref) batched else null }

        val host = ManagedHost(journalFor = selector)
        host.managementInlet.call.spawn(SetCell<String>(ref))

        // observable immediately after spawn returns - no runToIdle, no invocation sent
        host.durabilityAccounting().journaledSpawns[DurabilityClass.BATCHED] shouldBe 1L
        // and the stub's append was never called: the count is not derived from WAL activity
        batched.appendCount shouldBe 0
    }

    @Test
    fun `control - a single SYNCHRONOUS journal reads SYNCHRONOUS-1, others zero, and the deployment assertion passes`() {
        val dir = createTempDirectory("durability-accounting-control").toFile()
        val journal = FileJournal(dir.resolve("control.journal"))

        val host = ManagedHost(journal = journal)
        host.managementInlet.call.spawn(SetCell<String>())

        val acct = host.durabilityAccounting()
        acct.journaledSpawns[DurabilityClass.SYNCHRONOUS] shouldBe 1L
        acct.journaledSpawns[DurabilityClass.BATCHED] shouldBe 0L
        acct.journaledSpawns[DurabilityClass.IN_MEMORY] shouldBe 0L
        acct.volatileDurableSpawns shouldBe 0L

        // no exception: the deployment's own startup assertion passes
        check(
            acct.journaledSpawns[DurabilityClass.BATCHED] == 0L &&
                acct.journaledSpawns[DurabilityClass.IN_MEMORY] == 0L &&
                acct.volatileDurableSpawns == 0L
        )
    }

    /**
     * Feature seam (computenet-t6b.2 feature review): the stub above stands in for
     * `BatchedFileJournal` because t6b.2.3 could not depend on t6b.2.2. Here the real
     * class, served through the whole-host `journal` convenience and through a
     * per-cell `journalFor`, is counted as BATCHED end-to-end, and the deployment's
     * own startup assertion refuses it.
     */
    @Test
    fun `a real BatchedFileJournal is counted as BATCHED and fails the deployment assertion`() {
        val dir = createTempDirectory("durability-accounting-batched").toFile()
        val batched = BatchedFileJournal(dir.resolve("batched.journal"), syncEvery = 16)
        val sync = FileJournal(dir.resolve("sync.journal"))

        val wholeHost = ManagedHost(journal = batched)
        wholeHost.managementInlet.call.spawn(SetCell<String>())
        wholeHost.managementInlet.call.spawn(SetCell<String>())
        wholeHost.durabilityAccounting().journaledSpawns shouldBe mapOf(
            DurabilityClass.SYNCHRONOUS to 0L,
            DurabilityClass.BATCHED to 2L,
            DurabilityClass.IN_MEMORY to 0L,
        )

        val refBatched = CellRef(UUID.randomUUID())
        val refSync = CellRef(UUID.randomUUID())
        val mixed = ManagedHost(journalFor = { if (it == refBatched) batched else if (it == refSync) sync else null })
        mixed.managementInlet.call.spawn(SetCell<String>(refBatched))
        mixed.managementInlet.call.spawn(SetCell<String>(refSync))
        val acct = mixed.durabilityAccounting()
        acct.journaledSpawns shouldBe mapOf(
            DurabilityClass.SYNCHRONOUS to 1L,
            DurabilityClass.BATCHED to 1L,
            DurabilityClass.IN_MEMORY to 0L,
        )
        acct.volatileDurableSpawns shouldBe 0L
        shouldThrow<IllegalStateException> {
            check(acct.journaledSpawns[DurabilityClass.BATCHED] == 0L) {
                "relaxed durability in use: BATCHED=${acct.journaledSpawns[DurabilityClass.BATCHED]}"
            }
        }.message shouldContain "BATCHED=1"
    }

    @Test
    fun `volatileDurableSpawns is unchanged by this feature - ManifestDriftTest coverage restated here`() {
        val volatileHost = ManagedHost()
        volatileHost.managementInlet.call.spawn(SetCell<String>())
        volatileHost.volatileDurableSpawns() shouldBe 1L
        volatileHost.durabilityAccounting().volatileDurableSpawns shouldBe 1L
    }
}
