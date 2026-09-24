package civictech.timetravel.diff

import civictech.cell.durability.FileJournal
import civictech.timetravel.journal.FrameRecord
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.reconstruct.CellReconstruction
import civictech.timetravel.reconstruct.Reconstructor
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * TTD1 F6 (computenet-si0tl.4) BS-11: a run that continues past a shared WAVE-aligned prefix
 * diverges `ONLY_IN_B` at the extra add's proxy frame, and the state comparison at the aligned
 * prefix names a per-cell delta naming exactly the added element (`si0tl-D10`, `[TTD1-42]`,
 * `[TTD1-46]`, the report half of `[TTD1-45]`).
 *
 * `AMENDS computenet-si0tl.4`: this test kills the three mutations .3's reviewer left surviving on
 * `RunDiff.kt` — "always compare at the run's last index" (the `Compared(8, 9, ...)` assertion
 * below, `positionA != positionB`), "ignore view equality, count only presence" (the per-cell
 * `viewB` contains `"d"`, `viewA` does not), and "never list an unreconstructible run" is killed by
 * [StateDiffUnavailableTest], not here.
 */
class RunDiffDivergenceTest {

    private val scriptA = listOf(0 to "a", 0 to "b", 0 to "c")
    private val scriptB = scriptA + (0 to "d")

    private val keyPattern = Regex("(?i)time|clock|date|host|path")

    /**
     * Whether [view] names the OR-set element `"d"` — as an entry key (the union/view cells' tagged
     * map, keyed by element) or a whole-word token inside an entry's value (the source cell's `adds`
     * map, rendered as one string per entry). A whole-word match, not a bare substring: `"dels"` and
     * `"reclaimed"` both contain the letter `d` without naming the element.
     */
    private fun namesElementD(view: StateView): Boolean {
        val whole = Regex("\\bd\\b")
        return view.entries.any { it.key == "d" || whole.containsMatchIn(it.value) }
    }

    private fun assertNoVolatileKeys(json: String) {
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (k, v) ->
                    keyPattern.containsMatchIn(k) shouldBe false
                    walk(v)
                }

                is JsonArray -> element.forEach { walk(it) }
                else -> {}
            }
        }
        walk(Json.parseToJsonElement(json))
    }

    @Test
    fun `BS-11 a run that continues past the shared prefix diverges ONLY_IN_B with a per-cell state delta`(
        @TempDir dir: File,
    ) {
        val recA = RoutedRunFixture.record(seed = 11, sourceCount = 1, script = scriptA, journal = FileJournal(File(dir, "a.bin")))
        val recB = RoutedRunFixture.record(seed = 11, sourceCount = 1, script = scriptB, journal = FileJournal(File(dir, "b.bin")))

        val readingA = JournalReader.open(JournalSource.File(File(dir, "a.bin")))
        val readingB = JournalReader.open(JournalSource.File(File(dir, "b.bin")))
        val tA = RunTimeline.of(readingA).values.single()
        val tB = RunTimeline.of(readingB).values.single()
        val reconstructorA = Reconstructor(readingA, tA, RoutedRunFixture.graphSource(recA))
        val reconstructorB = Reconstructor(readingB, tB, RoutedRunFixture.graphSource(recB))

        tA.size shouldBe 9
        tB.size shouldBe 12

        val report = RunDiff.diff(tA, tB, reconstructorA, reconstructorB)

        report.alignment shouldBe AlignmentMode.WAVE
        report.alignedPrefix shouldBe 9
        report.sizeA shouldBe 9
        report.sizeB shouldBe 12

        val d = report.divergence!!
        d.kind shouldBe DivergenceClass.ONLY_IN_B
        d.indexA shouldBe null
        d.indexB shouldBe 9
        d.labelB!! shouldContain "#9"
        d.labelB!! shouldNotContain "wave"

        // The extra add is the (sourceA, next) wave of BS-11: proxy frame at 9 (contextless — the
        // reason labelB carries no "wave" token), the union's waved frame at 10, the view's at 11.
        tB.positions[10].wave!!.sourceId shouldBe recB.epochs[0]
        (tB.positions[11].record as FrameRecord).cellRef shouldBe recB.refs.view

        val unionCountersA = tA.positions.filterIndexed { i, _ -> i % 3 == 1 }.map { it.wave!!.counter }
        unionCountersA.isEmpty() shouldBe false
        unionCountersA.all { tB.positions[10].wave!!.counter > it } shouldBe true

        // [TTD1-42]: the state delta at the aligned prefix. Mutation 1 (`always compare at the run's
        // last index`) is killed by positionA=8 != positionB=9; mutation 2 (`ignore view equality`)
        // is killed by the viewA/viewB content assertions below.
        val faithful = FidelityDto(Verdict.FAITHFUL, emptyList())
        val stateDiff = report.stateDiff.shouldBeInstanceOf<StateDiff.Compared>()
        stateDiff.positionA shouldBe 8
        stateDiff.positionB shouldBe 9
        stateDiff.positionA shouldNotBe stateDiff.positionB
        stateDiff.runA shouldBe faithful
        stateDiff.runB shouldBe faithful

        val allRefs = recA.refs.all.map { it.id.toString() }.sorted()
        stateDiff.deltas.map { it.cellRef } shouldBe allRefs
        allRefs.isEmpty() shouldBe false

        val directA = reconstructorA.stateAt(Position.Index(8))
        stateDiff.deltas.forEach { delta ->
            namesElementD(delta.viewB!!) shouldBe true
            namesElementD(delta.viewA!!) shouldBe false

            val ref = recA.refs.all.first { it.id.toString() == delta.cellRef }
            val directView = (directA.cells.getValue(ref) as CellReconstruction.Reconstructed).view
            delta.viewA shouldBe StateView.of(directView)
        }

        // Negative control: the identical pair (recA vs a second `a b c` recording) has no deltas.
        val recA2 = RoutedRunFixture.record(seed = 11, sourceCount = 1, script = scriptA)
        val readingA2 = RoutedRunFixture.reading(recA2, "a2")
        val tA2 = RoutedRunFixture.timeline(readingA2)
        val reconstructorA2 = RoutedRunFixture.reconstructor(recA2, readingA2, tA2)
        val identical = RunDiff.diff(tA, tA2, reconstructorA, reconstructorA2)
        identical.stateDiff.shouldBeInstanceOf<StateDiff.Compared>().deltas.shouldBeEmpty()

        // [TTD1-45] report half: no volatile-shaped key, journal names with no directory component.
        assertNoVolatileKeys(report.toJson())
        val parsed = Json.parseToJsonElement(report.toJson()).jsonObject
        parsed.getValue("journalA").jsonPrimitive.content shouldBe "a.bin"
        parsed.getValue("journalB").jsonPrimitive.content shouldBe "b.bin"

        val text = report.toText()
        text shouldContain "ONLY_IN_B"
        text shouldContain "#9"
        stateDiff.deltas.forEach { delta -> text shouldContain delta.cellRef }
    }

    @Test
    fun `TTD1-46 diff is byte-identical across readings, and differs when sides swap`(@TempDir dir: File) {
        val recA = RoutedRunFixture.record(seed = 11, sourceCount = 1, script = scriptA, journal = FileJournal(File(dir, "a.bin")))
        val recB = RoutedRunFixture.record(seed = 11, sourceCount = 1, script = scriptB, journal = FileJournal(File(dir, "b.bin")))

        fun diffOverFileReading(): RunDiffReport {
            val readingA = JournalReader.open(JournalSource.File(File(dir, "a.bin")))
            val readingB = JournalReader.open(JournalSource.File(File(dir, "b.bin")))
            val tA = RunTimeline.of(readingA).values.single()
            val tB = RunTimeline.of(readingB).values.single()
            return RunDiff.diff(tA, tB, Reconstructor(readingA, tA, RoutedRunFixture.graphSource(recA)), Reconstructor(readingB, tB, RoutedRunFixture.graphSource(recB)))
        }

        val r1 = diffOverFileReading()
        val r2 = diffOverFileReading()
        val r3 = diffOverFileReading()

        val readingAMem = JournalReader.open(JournalSource.InMemory(recA.journal, "a.bin"))
        val readingBMem = JournalReader.open(JournalSource.InMemory(recB.journal, "b.bin"))
        val tAMem = RunTimeline.of(readingAMem).values.single()
        val tBMem = RunTimeline.of(readingBMem).values.single()
        val r4 = RunDiff.diff(
            tAMem, tBMem,
            Reconstructor(readingAMem, tAMem, RoutedRunFixture.graphSource(recA)),
            Reconstructor(readingBMem, tBMem, RoutedRunFixture.graphSource(recB)),
        )

        val jsons = listOf(r1, r2, r3, r4).map { it.toJson() }
        jsons.toSet().size shouldBe 1
        val bytes = jsons.map { it.toByteArray() }
        bytes.drop(1).forEach { it.contentEquals(bytes.first()) shouldBe true }

        // Negative control: the sides swapped is a different report.
        val readingASwap = JournalReader.open(JournalSource.File(File(dir, "a.bin")))
        val readingBSwap = JournalReader.open(JournalSource.File(File(dir, "b.bin")))
        val tASwap = RunTimeline.of(readingASwap).values.single()
        val tBSwap = RunTimeline.of(readingBSwap).values.single()
        val swapped = RunDiff.diff(
            tBSwap, tASwap,
            Reconstructor(readingBSwap, tBSwap, RoutedRunFixture.graphSource(recB)),
            Reconstructor(readingASwap, tASwap, RoutedRunFixture.graphSource(recA)),
        )
        swapped.toJson().toByteArray().contentEquals(r1.toJson().toByteArray()) shouldBe false
    }
}
