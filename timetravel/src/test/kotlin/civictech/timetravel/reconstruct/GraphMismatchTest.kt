package civictech.timetravel.reconstruct

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.timetravel.fidelity.Fidelity
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * TTD1 F4 (`computenet-6tm33.6`): a graph that does not match the journal is reported, never
 * thrown and never silently filtered.
 *
 * - BS-10, `[TTD1-24]`, 6tm33-D10: a journal naming a ref the rebuilt graph lacks yields
 *   `GraphMismatch(ref)` and `GRAPH_MISMATCH`, every record is still fed to recovery (the kernel
 *   dead-letters the unroutable frames and continues), and the present cells are reconstructed.
 * - D2 / 6tm33-D11: a [GraphSource] that spawns a cell it does not return is refused before
 *   replay (`GRAPH_SOURCE_INCOMPLETE`).
 */
class GraphMismatchTest {

    private val script = listOf(0 to "a1", 0 to "a2", 1 to "b1", 0 to "a3", 1 to "b2", 0 to "a4")

    private fun reconstructorOf(recording: DurableGraphFixture.Recording, graph: GraphSource): Pair<Reconstructor, RunTimeline> {
        val reading = JournalReader.open(JournalSource.InMemory(recording.journal, "j"))
        val timeline = RunTimeline.of(reading).getValue("j")
        return Reconstructor(reading, timeline, graph) to timeline
    }

    @Test
    fun `BS-10 a journal naming a cell the graph lacks reports GraphMismatch, feeds every record and keeps the rest`() {
        val recording = DurableGraphFixture.record(seed = 21, sourceCount = 2, script = script)
        val missing = recording.refs.sources[1]
        val withoutS1 = GraphSpec(
            recording.spec.steps.filterNot {
                it is SpawnStep && it.handle == "s1" || it is ConnectStep && (it.from == "s1" || it.to == "s1")
            },
        )
        withoutS1.steps.size shouldBe recording.spec.steps.size - 2
        val (reconstructor, timeline) = reconstructorOf(recording, GraphSpecSource(withoutS1))
        // Non-vacuity: the journal really names the removed source.
        timeline.positions.any { missing in it.touches } shouldBe true

        val last = timeline.size - 1
        val reconstruction = reconstructor.stateAt(Position.Index(last))

        reconstruction.details shouldBe setOf(GraphMismatch(missing))
        (Reason.GRAPH_MISMATCH in reconstruction.run.reasons) shouldBe true
        reconstruction.run shouldNotBe Fidelity.Faithful
        reconstruction.position shouldBe ResolvedPosition(Position.Index(last), timeline.size, 0, timeline.size)
        reconstruction.position.replayedRecords shouldBe timeline.size
        reconstruction.cells.keys shouldNotContain missing
        for (ref in listOf(recording.refs.sources[0], recording.refs.union, recording.refs.view)) {
            reconstruction.cells.getValue(ref).shouldBeInstanceOf<CellReconstruction.Reconstructed>()
        }
    }

    @Test
    fun `a graph source that spawns a cell it does not return is refused before replay`() {
        val recording = DurableGraphFixture.record(seed = 21, sourceCount = 2, script = script)
        val extraRef = CellRef(UUID(21, 999))
        val leaky = GraphSource { host ->
            GraphSpecSource(recording.spec).build(host).also {
                host.managementInlet.call.spawn(SetCell<String>(extraRef))
            }
        }
        val (reconstructor, timeline) = reconstructorOf(recording, leaky)
        val requested = Position.Index(timeline.size - 1)

        val reconstruction = reconstructor.stateAt(requested)

        val refused = Fidelity.Unreconstructible(setOf(Reason.GRAPH_SOURCE_INCOMPLETE))
        reconstruction.cells.keys shouldBe recording.refs.all + extraRef
        for ((_, cell) in reconstruction.cells) {
            cell.shouldBeInstanceOf<CellReconstruction.Unreconstructible>().fidelity shouldBe refused
        }
        reconstruction.details shouldBe setOf(GraphSourceIncomplete(setOf(extraRef)))
        reconstruction.run.shouldBeInstanceOf<Fidelity.Unreconstructible>()
        reconstruction.position.replayedRecords shouldBe 0
        reconstruction.position.prefixEnd shouldBe timeline.size
    }
}
