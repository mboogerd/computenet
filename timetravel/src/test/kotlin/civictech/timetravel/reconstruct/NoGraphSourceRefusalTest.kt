package civictech.timetravel.reconstruct

import civictech.timetravel.fidelity.Reason
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * TTD1 F4 (`computenet-6tm33.4`) BS-9, API half: with no graph source there is nothing to replay
 * into, and [Reconstructor.of] refuses with a pointer to the spec (`[TTD1-23]`); with one it is
 * `Ready` and indistinguishable from a directly constructed reconstructor. Also pins that
 * `stateAt` catches nothing but the kernel's `RecoveryIncomplete` (6tm33-D12).
 */
class NoGraphSourceRefusalTest {

    private val recording = DurableGraphFixture.record(
        seed = 11,
        sourceCount = 1,
        script = listOf(0 to "a", 0 to "b", 0 to "c"),
    )
    private val reading = JournalReader.open(JournalSource.InMemory(recording.journal, "j"))
    private val timeline = RunTimeline.of(reading).getValue("j")

    @Test
    fun `of with no graph source refuses NO_GRAPH_SOURCE, citing the durability spectrum`() {
        val refusal = Reconstructor.of(reading, timeline, null).shouldBeInstanceOf<ReconstructorResult.Refusal>()

        refusal.reason shouldBe Reason.NO_GRAPH_SOURCE
        refusal.message shouldContain "24-data-cells.md"
        refusal.message shouldContain "Durability spectrum"
    }

    @Test
    fun `of with a graph source is Ready and reconstructs exactly as a direct Reconstructor does`() {
        val ready = Reconstructor.of(reading, timeline, GraphSpecSource(recording.spec))
            .shouldBeInstanceOf<ReconstructorResult.Ready>()
        val requested = Position.Index(recording.steps[1].lastIndex)

        val direct = Reconstructor(reading, timeline, GraphSpecSource(recording.spec)).stateAt(requested)

        ready.reconstructor.stateAt(requested) shouldBe direct
    }

    @Test
    fun `a graph source that throws propagates out of stateAt uncaught`() {
        val boom = GraphSource { throw IllegalStateException("boom") }

        val thrown = shouldThrow<IllegalStateException> {
            Reconstructor(reading, timeline, boom).stateAt(Position.Index(0))
        }

        thrown.message shouldBe "boom"
    }
}
