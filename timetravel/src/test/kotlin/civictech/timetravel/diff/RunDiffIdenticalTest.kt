package civictech.timetravel.diff

import civictech.cell.durability.FileJournal
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.reconstruct.Reconstructor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * TTD1 F6 (computenet-si0tl.3) BS-12, API half: two seeded routed recordings of one script diff as
 * identical — full aligned prefix, no divergence, and (with reconstructors) no state delta
 * (`[TTD1-39]`, `si0tl-D10`); without reconstructors the record-level fields are unchanged and the
 * state is `Unavailable` naming both runs. Also D5 / `si0tl-D12`: [RunDiff.diffReadings] matches
 * journals by base name.
 */
class RunDiffIdenticalTest {

    private val script = listOf(0 to "a", 0 to "b", 0 to "c")

    private val faithful = FidelityDto(Verdict.FAITHFUL, emptyList())

    /** No host path — nor any `/` — reaches a report's journal names (`si0tl-D12`). */
    private fun assertNoPathIn(report: RunDiffReport) {
        val json = Json.parseToJsonElement(report.toJson()).jsonObject
        json.getValue("journalA").jsonPrimitive.content shouldNotContain "/"
        json.getValue("journalB").jsonPrimitive.content shouldNotContain "/"
    }

    @Test
    fun `BS-12 two same-seed routed recordings are byte-identical and diff as identical, with and without state`() {
        val recA = RoutedRunFixture.record(seed = 11, sourceCount = 1, script = script)
        val recB = RoutedRunFixture.record(seed = 11, sourceCount = 1, script = script)

        // The seeded-determinism premise (si0tl-D13, `unverified:` at breakdown): byte for byte.
        val bytesA = recA.journal.replay()
        val bytesB = recB.journal.replay()
        bytesA.size shouldBe bytesB.size
        bytesA.zip(bytesB).forEach { (x, y) -> x.contentEquals(y) shouldBe true }

        val readingA = RoutedRunFixture.reading(recA, "a")
        val readingB = RoutedRunFixture.reading(recB, "b")
        val tA = RoutedRunFixture.timeline(readingA)
        val tB = RoutedRunFixture.timeline(readingB)

        RunDiff.align(tA, tB) shouldBe RecordAlignment.align(tA, tB)

        val withState = RunDiff.diff(
            tA, tB,
            RoutedRunFixture.reconstructor(recA, readingA, tA),
            RoutedRunFixture.reconstructor(recB, readingB, tB),
        )
        withState shouldBe RunDiffReport(
            journalA = "a", journalB = "b", sizeA = 9, sizeB = 9,
            alignment = AlignmentMode.WAVE, alignmentNote = null, alignedPrefix = 9, divergence = null,
            stateDiff = StateDiff.Compared(8, 8, faithful, faithful, emptyList()),
        )

        val recordOnly = RunDiff.diff(tA, tB)
        recordOnly shouldBe withState.copy(
            stateDiff = StateDiff.Unavailable(
                listOf(
                    RunUnavailable(RunSide.A, listOf(Reason.NO_GRAPH_SOURCE), Reconstructor.NO_GRAPH_SOURCE_MESSAGE),
                    RunUnavailable(RunSide.B, listOf(Reason.NO_GRAPH_SOURCE), Reconstructor.NO_GRAPH_SOURCE_MESSAGE),
                ),
            ),
        )
        assertNoPathIn(withState)
        assertNoPathIn(recordOnly)
    }

    @Test
    fun `D5 diffReadings matches journals by base name, sorted, across two directories`(@TempDir tmp: File) {
        val x = File(tmp, "x").apply { mkdirs() }
        val y = File(tmp, "y").apply { mkdirs() }
        val recA = RoutedRunFixture.record(seed = 11, sourceCount = 1, script = script, journal = FileJournal(File(x, "a.bin")))
        File(x, "a.bin").copyTo(File(x, "z.bin"))
        RoutedRunFixture.record(seed = 11, sourceCount = 1, script = script, journal = FileJournal(File(y, "a.bin")))

        val readingA = JournalReader.open(JournalSource.Directory(x))
        val readingB = JournalReader.open(JournalSource.Directory(y))

        val recordOnly = RunDiff.diffReadings(readingA, readingB)
        recordOnly.journals.map { it.journal to it.presence } shouldBe listOf(
            "a.bin" to JournalPresence.BOTH,
            "z.bin" to JournalPresence.ONLY_IN_A,
        )
        recordOnly.journals[0].report!!.divergence shouldBe null
        recordOnly.journals[0].report!!.stateDiff.shouldBeInstanceOf<StateDiff.Unavailable>()
        recordOnly.journals[1].report shouldBe null

        val graph = RoutedRunFixture.graphSource(recA)
        val withState = RunDiff.diffReadings(readingA, readingB, graph, graph)
        withState.journals.map { it.journal to it.presence } shouldBe listOf(
            "a.bin" to JournalPresence.BOTH,
            "z.bin" to JournalPresence.ONLY_IN_A,
        )
        val report = withState.journals[0].report!!
        report.divergence shouldBe null
        report.journalA shouldBe "a.bin"
        report.journalB shouldBe "a.bin"
        report.stateDiff shouldBe StateDiff.Compared(8, 8, faithful, faithful, emptyList())
        withState.journals[1].report shouldBe null

        // Each side's graph source reaches that side's reconstructor: only B lacks one here.
        val onlyA = RunDiff.diffReadings(readingA, readingB, graph, null)
        onlyA.journals[0].report!!.stateDiff shouldBe StateDiff.Unavailable(
            listOf(RunUnavailable(RunSide.B, listOf(Reason.NO_GRAPH_SOURCE), Reconstructor.NO_GRAPH_SOURCE_MESSAGE)),
        )

        for (multi in listOf(recordOnly, withState)) {
            multi.journals.mapNotNull { it.report }.forEach { assertNoPathIn(it) }
            val journals = Json.parseToJsonElement(multi.toJson()).jsonObject.getValue("journals")
            (journals as kotlinx.serialization.json.JsonArray).forEach {
                it.jsonObject.getValue("journal").jsonPrimitive.content shouldNotContain "/"
            }
        }
    }
}
