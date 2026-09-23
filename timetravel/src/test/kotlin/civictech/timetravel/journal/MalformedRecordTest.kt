package civictech.timetravel.journal

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.durability.FileJournal
import civictech.cell.durability.InMemoryJournal
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import civictech.timetravel.fidelity.Reason
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

/**
 * TTD1 F1 (computenet-wzbww.4): a record of a known type whose payload cannot be read is
 * reported and reading continues past it — never a silent skip, never an exception (BS-6,
 * `[TTD1-08]`, `[TTD1-10]`). Pins `JournalReader.CHECKPOINT_TYPE = 2`, which was previously
 * unpinned by any test (see the AMENDS comment on this bead).
 */
class MalformedRecordTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private fun spawn(journal: civictech.cell.durability.Journal, seed: Long): Pair<ManagedHost, CellRef> {
        val controller = SimulationController(seed = seed)
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val ref = CellRef(UUID(seed, seed))
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        val api = (HostedCellProxy.create(ref, host, SetInletProxy::class.java) as SetInletProxy).inlet.call
        api.add("e0")
        controller.runToIdle()
        return host to ref
    }

    private fun classificationOf(record: JournalRecord): Any = when (record) {
        is UnknownRecord -> "Unknown:${record.typeByte}"
        is MalformedRecord -> "Malformed:${record.typeByte}:${record.reason}"
        is CheckpointRecord -> "Checkpoint"
        is FrameRecord -> "Frame"
        is FrontierRecord -> "Frontier"
        is OutletWaveRecord -> "OutletWave"
        is BaselineDischargeRecord -> "BaselineDischarge"
    }

    @Test
    fun `BS-6 every defect is reported at its own index and reading continues, in memory and from a file`(
        @TempDir dir: File,
    ) {
        val journal = InMemoryJournal()
        val (host, _) = spawn(journal, seed = 51)
        host.checkpoint(journal)
        val checkpointBytes = journal.replay()[0]
        checkpointBytes[0] shouldBe 2.toByte() // sanity: index 0 really is the checkpoint record
        val base = journal.replay().size

        // a genuine RECORD_FRAME copied from a second, unrelated host's journal
        val framesJournal = InMemoryJournal()
        spawn(framesJournal, seed = 52)
        val frameBytes = framesJournal.replay()[0]
        frameBytes[0] shouldBe 1.toByte()

        journal.append(byteArrayOf(9, 1, 2, 3)) // base: unknown type
        journal.append(byteArrayOf(2) + ByteArray(checkpointBytes.size - 1)) // base+1: corrupted checkpoint
        journal.append(byteArrayOf()) // base+2: empty record
        journal.append(byteArrayOf(3) + ByteArray(16)) // base+3: corrupted frontier-shaped
        journal.append(byteArrayOf(1) + "not json".encodeToByteArray()) // base+4: unparseable frame
        journal.append(frameBytes) // base+5: healthy frame

        val reading = JournalReader.open(JournalSource.InMemory(journal, "m"))
        val records = reading.records.toList()
        records.size shouldBe (base + 6)

        records[base] shouldBe UnknownRecord(index = base, journalId = "m", typeByte = 9)

        val corruptedCheckpoint = records[base + 1].shouldBeInstanceOf<MalformedRecord>()
        corruptedCheckpoint.index shouldBe (base + 1)
        corruptedCheckpoint.typeByte shouldBe 2.toByte()
        corruptedCheckpoint.reason shouldBe Reason.CHECKPOINT_UNDESERIALIZABLE
        corruptedCheckpoint.message.shouldNotBeBlank()

        records[base + 2] shouldBe UnknownRecord(index = base + 2, journalId = "m", typeByte = null)

        val corruptedFrontier = records[base + 3].shouldBeInstanceOf<MalformedRecord>()
        corruptedFrontier.index shouldBe (base + 3)
        corruptedFrontier.typeByte shouldBe 3.toByte()
        corruptedFrontier.reason shouldBe Reason.RECORD_UNDESERIALIZABLE

        val unparseableFrame = records[base + 4].shouldBeInstanceOf<MalformedRecord>()
        unparseableFrame.index shouldBe (base + 4)
        unparseableFrame.typeByte shouldBe 1.toByte()
        unparseableFrame.reason shouldBe Reason.FRAME_UNPARSEABLE

        val healthy = records[base + 5].shouldBeInstanceOf<FrameRecord>()
        healthy.index shouldBe (base + 5)

        reading.reasons shouldContain Reason.UNKNOWN_RECORD
        reading.reasons shouldContain Reason.CHECKPOINT_UNDESERIALIZABLE
        reading.reasons shouldContain Reason.RECORD_UNDESERIALIZABLE
        reading.reasons shouldContain Reason.FRAME_UNPARSEABLE

        // the corruption did not leak into the real record
        records[0].shouldBeInstanceOf<CheckpointRecord>()

        // the file path and the in-memory path agree, at the same indices
        val file = File(dir, "m.bin")
        val fileJournal = FileJournal(file)
        journal.replay().forEach { fileJournal.append(it) }
        val fileRecords = JournalReader.open(JournalSource.File(file)).records.toList()
        fileRecords.map(::classificationOf) shouldBe records.map(::classificationOf)
    }
}
