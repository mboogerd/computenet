package civictech.timetravel.reconstruct

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.timetravel.fidelity.Fidelity
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * TTD1 F4 (`computenet-6tm33.4`) BS-1: `stateAt(Index(i))` replays the prefix `[0, i]` through the
 * kernel's `recoverFrom` on an offline host (`[TTD1-20]`, `[TTD1-21]`, `[TTD1-22]`) and reproduces
 * every cell's live snapshot at that point; and a non-`Stateful` cell is `Unreconstructible` with
 * no value (`[TTD1-25]`).
 */
class ReconstructAtWaveTest {

    /** A cell with no state at all — the `[TTD1-25]` case. Not `Stateful`, and has no effects. */
    class Opaque(override val ref: CellRef) : Cell

    private val script = listOf("a", "b", "c", "d", "e").map { 0 to it }

    private fun reconstructorFor(recording: DurableGraphFixture.Recording, graph: GraphSource): Reconstructor {
        val reading = JournalReader.open(JournalSource.InMemory(recording.journal, "j"))
        val timeline = RunTimeline.of(reading).getValue("j")
        return Reconstructor(reading, timeline, graph)
    }

    @Test
    fun `stateAt each step's last record reproduces every cell's live snapshot, faithfully`() {
        val recording = DurableGraphFixture.record(seed = 11, sourceCount = 1, script = script)
        val reconstructor = reconstructorFor(recording, GraphSpecSource(recording.spec))

        for (k in 1..3) {
            val step = recording.steps[k]
            val requested = Position.Index(step.lastIndex)

            val reconstruction = reconstructor.stateAt(requested)

            reconstruction.position shouldBe
                ResolvedPosition(requested, step.lastIndex + 1, 0, step.lastIndex + 1)
            reconstruction.run shouldBe Fidelity.Faithful
            reconstruction.details.shouldBeEmpty()
            reconstruction.cells.keys shouldBe recording.refs.all
            for (ref in recording.refs.all) {
                val cell = reconstruction.cells.getValue(ref).shouldBeInstanceOf<CellReconstruction.Reconstructed>()
                cell.snapshot shouldBe step.liveSnapshots.getValue(ref)
                cell.fidelity shouldBe Fidelity.Faithful
                cell.view shouldBe CellStateView.of(cell.snapshot)
            }
        }
    }

    @Test
    fun `a cell that is not Stateful is Unreconstructible NOT_STATEFUL with no value, the rest unchanged`() {
        val recording = DurableGraphFixture.record(seed = 11, sourceCount = 1, script = script)
        val opaqueRef = CellRef(UUID(11, 999))
        val withOpaque = GraphSource { host ->
            val base = GraphSpecSource(recording.spec).build(host)
            val opaque = Opaque(opaqueRef)
            host.managementInlet.call.spawn(opaque)
            GraphBuild(base.cells + opaque)
        }
        val requested = Position.Index(recording.steps[2].lastIndex)

        val plain = reconstructorFor(recording, GraphSpecSource(recording.spec)).stateAt(requested)
        val reconstruction = reconstructorFor(recording, withOpaque).stateAt(requested)

        reconstruction.cells.getValue(opaqueRef) shouldBe CellReconstruction.Unreconstructible(
            Opaque::class.java.name,
            Fidelity.Unreconstructible(setOf(Reason.NOT_STATEFUL)),
        )
        reconstruction.cells - opaqueRef shouldBe plain.cells
        reconstruction.run shouldBe Fidelity.Unreconstructible(setOf(Reason.NOT_STATEFUL))
        reconstruction.details.shouldBeEmpty()
    }
}
