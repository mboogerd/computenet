package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.control.AttentionPolicy
import civictech.cell.control.AttentionSupport
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.JournalRecords
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression for computenet-bv7qb: attention parking happens after intake accepted and
 * journaled a frame. Renewed attention replays that accepted work live, but must not append
 * the same frame to the write-ahead journal a second time.
 */
class AttentionParkJournalTest {

    /**
     * Non-idempotent and non-Stateful: replayed frames are its only recovery source, and a
     * duplicate frame remains observable as a duplicate list entry rather than merging away.
     */
    private class TallyCell(override val ref: CellRef) : Cell {
        val received = mutableListOf<Int>()
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    received += input
                }
            })
        }
    }

    private interface TallyProxy {
        val inlet: Use<Consumer<Int>>
    }

    private data class ParkedSession(
        val journal: InMemoryJournal,
        val ref: CellRef,
    )

    private fun parkThenUnpark(): ParkedSession {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID())
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            journal = journal,
            attention = AttentionPolicy(suspendAfter = 3),
        )
        val cell = TallyCell(ref)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()

        AttentionSupport.of(cell).attend(0f)
        val inlet = (HostedCellProxy.create(ref, host, TallyProxy::class.java) as TallyProxy).inlet.call
        (1..10).forEach(inlet::provide)
        controller.runToIdle()
        withClue("the attention window delivers three frames and parks the remaining seven") {
            cell.received shouldBe (1..3).toList()
        }

        AttentionSupport.of(cell).attend(1f)
        controller.runToIdle()
        cell.received shouldBe (1..10).toList()
        return ParkedSession(journal, ref)
    }

    @Test
    fun `unparking already-accepted frames does not append them to the journal again`() {
        val session = parkThenUnpark()

        withClue("each of the ten accepted frames must have exactly one RECORD_FRAME") {
            session.journal.replay().count { JournalRecords.decode(it) is DecodedJournalRecord.Frame } shouldBe 10
        }
    }

    @Test
    fun `crash recovery applies each unparked frame once to a non-idempotent cell`() {
        val session = parkThenUnpark()

        // Crash: only the journal survives. A fresh host and non-idempotent cell replay it.
        val recoveredController = SimulationController(seed = 2)
        val recoveredHost = ManagedHost(scheduler = recoveredController.scheduler(), journal = session.journal)
        val recoveredCell = TallyCell(session.ref)
        recoveredHost.managementInlet.call.spawn(recoveredCell)
        recoveredController.runToIdle()
        recoveredHost.recoverFrom(session.journal)
        recoveredController.runToIdle()

        recoveredCell.received shouldBe (1..10).toList()
    }
}
