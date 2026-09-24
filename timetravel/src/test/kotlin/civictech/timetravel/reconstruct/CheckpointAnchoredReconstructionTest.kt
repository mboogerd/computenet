package civictech.timetravel.reconstruct

import civictech.timetravel.fidelity.Fidelity
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.CheckpointRecord
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * TTD1 F4 (`computenet-6tm33.5`) BS-4, `[TTD1-29]`: where record 0 of a journal is a checkpoint
 * (the only place `HostDurability.checkpoint` ever puts one — it compacts the journal to
 * `[checkpoint, outlet-wave records…]` and later appends form the tail), the reconstructor replays
 * from it, and reaches the same per-cell state as an uncompacted recording of the same script and
 * seed.
 */
class CheckpointAnchoredReconstructionTest {

    private val script = listOf("a", "b", "c", "d").map { 0 to it }

    private fun reconstruct(recording: DurableGraphFixture.Recording): Pair<RunTimeline, Reconstructor> {
        val reading = JournalReader.open(JournalSource.InMemory(recording.journal, "j"))
        val timeline = RunTimeline.of(reading).values.single()
        return timeline to Reconstructor(reading, timeline, GraphSpecSource(recording.spec))
    }

    @Test
    fun `a checkpoint at record 0 is the anchor, and replay from it equals the uncompacted history`() {
        val compacted = DurableGraphFixture.record(seed = 4, sourceCount = 1, script = script, checkpointAfter = 3)
        val plain = DurableGraphFixture.record(seed = 4, sourceCount = 1, script = script)

        val (compactedTimeline, compactedReconstructor) = reconstruct(compacted)
        val (plainTimeline, plainReconstructor) = reconstruct(plain)

        compactedTimeline.positions[0].isAnchor shouldBe true
        compactedTimeline.positions[0].record.shouldBeInstanceOf<CheckpointRecord>()
        withClue("compaction folded a b c into record 0: no other checkpoint, no replay before it") {
            compactedTimeline.positions.count { it.record is CheckpointRecord } shouldBe 1
        }

        val last = Position.Index(compactedTimeline.size - 1)
        val compactedOffline = compactedReconstructor.stateAt(last)
        compactedOffline.position.anchor shouldBe 0
        compactedOffline.position.replayedRecords shouldBe compactedTimeline.size
        compactedOffline.run shouldBe Fidelity.Faithful

        val plainOffline = plainReconstructor.stateAt(Position.Index(plainTimeline.size - 1))
        compactedOffline.cells.keys shouldBe compacted.refs.all
        for (ref in compacted.refs.all) {
            withClue("cell $ref") {
                val c = compactedOffline.cells.getValue(ref).shouldBeInstanceOf<CellReconstruction.Reconstructed>()
                val p = plainOffline.cells.getValue(ref).shouldBeInstanceOf<CellReconstruction.Reconstructed>()
                c.snapshot shouldBe p.snapshot
            }
        }
    }

    @Test
    fun `the checkpoint alone restores the source's membership as of the checkpointed step`() {
        val compacted = DurableGraphFixture.record(seed = 4, sourceCount = 1, script = script, checkpointAfter = 3)
        val plain = DurableGraphFixture.record(seed = 4, sourceCount = 1, script = script)
        val source = compacted.refs.sources.single()

        val (_, reconstructor) = reconstruct(compacted)
        val atCheckpoint = reconstructor.stateAt(Position.Index(0))

        atCheckpoint.position.anchor shouldBe 0
        atCheckpoint.position.replayedRecords shouldBe 1
        val cell = atCheckpoint.cells.getValue(source).shouldBeInstanceOf<CellReconstruction.Reconstructed>()
        val membership = addsKeys(cell.snapshot)
        membership shouldBe addsKeys(plain.steps[2].liveSnapshots.getValue(source))
        membership shouldBe setOf("a", "b", "c")
    }

    /** The element keys of a `SetCell` snapshot's `"adds"` map. */
    private fun addsKeys(snapshot: java.io.Serializable): Set<Any?> =
        ((snapshot as Map<*, *>)["adds"] as Map<*, *>).keys.toSet()
}
