package civictech.cell.durability

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.IntakeBound
import civictech.cell.host.ManagedHost
import civictech.cell.host.RecoveryIncomplete
import civictech.cell.host.SaturationPolicy
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * T05 finding 4: `HostDurability.recoverFrom`'s bare `forEach` over
 * `journal.replay()` had no per-record handling — any decode/`readObject`
 * throw abandoned every remaining record silently (`recovering` still reset
 * in the `finally`, so the host resumed live traffic on truncated state with
 * nothing to say so). Worse, `submit` is `enqueueHostedInvocation`, which
 * throws `IntakeSaturatedException` once a durable host's `intakeBound`
 * high-water is exceeded — since nothing drains during the synchronous
 * replay, a journal longer than high-water deterministically aborted
 * recovery.
 */
class RecoveryAccountingTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private fun writeJournal(elements: Int): Pair<InMemoryJournal, CellRef> {
        val controller = SimulationController()
        val journal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID())
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        val api = (HostedCellProxy.create(ref, host, SetInletProxy::class.java) as SetInletProxy).inlet.call
        repeat(elements) { api.add("e$it") }
        controller.runToIdle()
        return journal to ref
    }

    @Test
    fun `a journal longer than intakeBound high-water recovers fully instead of aborting`() {
        val elements = 12
        val (journal, ref) = writeJournal(elements)
        (journal.replay().size >= elements).shouldBeTrue() // one frame per add, at least

        val controller = SimulationController()
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            intakeBound = IntakeBound(highWater = 2, lowWater = 0, policy = SaturationPolicy.Park),
        )
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()

        // pre-fix: throws IntakeSaturatedException partway through the
        // synchronous replay loop, since nothing drains until it returns
        host.recoverFrom(journal)
        controller.runToIdle()

        cell.membership() shouldBe (0 until elements).map { "e$it" }.toSet()
    }

    @Test
    fun `a corrupted middle record throws RecoveryIncomplete with its index and dead-letters it, not the whole batch`() {
        val (journal, ref) = writeJournal(5)
        val records = journal.replay()
        (records.size >= 3).shouldBeTrue()
        val corruptAt = records.size / 2

        val corrupting = object : Journal {
            override fun append(record: ByteArray) = journal.append(record)
            override fun replay(): List<ByteArray> =
                journal.replay().mapIndexed { i, r -> if (i == corruptAt) byteArrayOf(99) else r }
            override fun reset(records: List<ByteArray>) = journal.reset(records)
        }

        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()

        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(
            Use.fixed(object : Propagate<DeadLetter> {
                override fun propagate(value: DeadLetter) { letters += value }
            }, PortRef.generate()),
        )

        val failure = shouldThrow<RecoveryIncomplete> { host.recoverFrom(corrupting) }
        failure.recordIndex shouldBe corruptAt
        failure.total shouldBe records.size

        controller.runToIdle()
        letters.size shouldBe 1
    }
}
