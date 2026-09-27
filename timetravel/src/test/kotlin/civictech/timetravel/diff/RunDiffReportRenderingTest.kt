package civictech.timetravel.diff

import civictech.timetravel.fidelity.Fidelity
import civictech.timetravel.fidelity.Reason
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.Test

/**
 * `RunDiffReport`/`MultiJournalDiffReport`: canonical `@Serializable` DTOs, one `Json` instance,
 * and the text rendering (TTD1 F6, computenet-si0tl.2, `si0tl-D11`, `si0tl-D13`, [TTD1-45]).
 */
class RunDiffReportRenderingTest {

    // --- fixtures -----------------------------------------------------------------------------

    private val degradedFidelity = FidelityDto.of(Fidelity.Degraded(setOf(Reason.UNKNOWN_DETERMINISM, Reason.VOLATILE_CELL)))
    private val faithfulFidelity = FidelityDto.of(Fidelity.Faithful)

    private val comparedDeltas = listOf(
        CellDelta(
            cellRef = "11111111-0000-0000-0000-000000000000",
            viewA = StateView("set", "1 rows", listOf(StateViewEntry("0", "x"))),
            viewB = StateView("set", "2 rows", listOf(StateViewEntry("0", "x"), StateViewEntry("1", "y"))),
            fidelityA = faithfulFidelity,
            fidelityB = degradedFidelity,
        ),
        CellDelta(
            cellRef = "22222222-0000-0000-0000-000000000000",
            viewA = null,
            viewB = StateView("scalar", "y", listOf(StateViewEntry("value", "y"))),
            fidelityA = null,
            fidelityB = faithfulFidelity,
        ),
        CellDelta(
            cellRef = "33333333-0000-0000-0000-000000000000",
            viewA = null,
            viewB = null,
            fidelityA = FidelityDto.of(Fidelity.Unreconstructible(setOf(Reason.NOT_STATEFUL))),
            fidelityB = faithfulFidelity,
        ),
    )

    private fun fullReport(): RunDiffReport = RunDiffReport(
        journalA = "a.bin",
        journalB = "b.bin",
        sizeA = 9,
        sizeB = 10,
        alignment = AlignmentMode.WAVE,
        alignmentNote = null,
        alignedPrefix = 8,
        divergence = Divergence(
            kind = DivergenceClass.STATE_DIFFERS,
            indexA = 8,
            indexB = 8,
            labelA = "#8 wave(...)",
            labelB = "#8 wave(...)",
            keyA = "wave(aaaaaaaa, 3) 11111111.inlet",
            keyB = "wave(aaaaaaaa, 3) 11111111.inlet",
            detailA = null,
            detailB = null,
        ),
        stateDiff = StateDiff.Compared(
            positionA = 8,
            positionB = 8,
            runA = faithfulFidelity,
            runB = degradedFidelity,
            deltas = comparedDeltas,
        ),
    )

    private fun unavailableReport(): RunDiffReport = RunDiffReport(
        journalA = "a.bin",
        journalB = "b.bin",
        sizeA = 3,
        sizeB = 3,
        alignment = AlignmentMode.INDEX_ONLY,
        alignmentNote = "runs are not wave-alignable: no shared sourceId",
        alignedPrefix = 3,
        divergence = null,
        stateDiff = StateDiff.Unavailable(
            runs = listOf(
                RunUnavailable(RunSide.A, listOf(Reason.NO_GRAPH_SOURCE), "no graph source for run A"),
                RunUnavailable(RunSide.B, listOf(Reason.NOT_STATEFUL), "cell is not Stateful"),
            ),
        ),
    )

    // --- reflective shape [TTD1-45] -------------------------------------------------------------

    @Test
    fun `TTD1-45 every reachable descriptor is a value shape, no Map, no leaked internal type`() {
        val forbidden = Regex(
            "^(java\\.util\\.UUID|civictech\\.cell\\.CellRef|civictech\\.timetravel\\.fidelity\\..*|" +
                "civictech\\.timetravel\\.reconstruct\\..*)$",
        )
        // Not deduplicated by serialName: kotlinx gives every List<T> the same generic collection
        // serialName regardless of T, so name-based memoization would skip real element types.
        // The DTO tree here is finite (no self-reference), so a depth cap is enough to stay safe.
        fun walk(descriptor: SerialDescriptor, depth: Int) {
            check(depth < 40) { "descriptor tree deeper than expected at ${descriptor.serialName}" }
            (descriptor.kind is StructureKind.MAP) shouldBe false
            val name = descriptor.serialName.substringBefore('?')
            val isReasonOrItsEntry = name == "civictech.timetravel.fidelity.Reason" ||
                name.startsWith("civictech.timetravel.fidelity.Reason.")
            if (forbidden.matches(name) && !isReasonOrItsEntry) {
                error("descriptor $name is not allowed in RunDiffReport's shape")
            }
            for (child in descriptor.elementDescriptors) {
                walk(child, depth + 1)
            }
        }

        walk(RunDiffReport.serializer().descriptor, 0)
    }

    // --- JSON key vocabulary and structure [TTD1-45] --------------------------------------------

    @Test
    fun `every JSON key is an element name reachable from the descriptor, no Map, no forbidden field name`() {
        val validKeys = collectElementNames(RunDiffReport.serializer().descriptor)

        val json = fullReport().toJson()
        val element = Json.parseToJsonElement(json)

        val forbiddenKeyPattern = Regex("(?i)time|clock|date|host|path")
        val allKeys = HashSet<String>()
        collectKeys(element, allKeys)

        val missing = allKeys.filterNot { it in validKeys }
        missing shouldBe emptyList<String>()

        allKeys.forEach { key ->
            forbiddenKeyPattern.containsMatchIn(key) shouldBe false
        }
    }

    private fun collectElementNames(
        descriptor: SerialDescriptor,
        keys: MutableSet<String> = HashSet(),
        depth: Int = 0,
    ): Set<String> {
        check(depth < 40) { "descriptor tree deeper than expected at ${descriptor.serialName}" }
        for (name in descriptor.elementNames) keys += name
        for (child in descriptor.elementDescriptors) collectElementNames(child, keys, depth + 1)
        return keys
    }

    private fun collectKeys(element: JsonElement, into: MutableSet<String>) {
        when (element) {
            is JsonObject -> element.entries.forEach { (key, value) ->
                into += key
                collectKeys(value, into)
            }

            is JsonArray -> element.forEach { collectKeys(it, into) }
            else -> {}
        }
    }

    // --- compactness, defaults, nulls, byte-equality [TTD1-45] ----------------------------------

    @Test
    fun `toJson is compact and emits defaults and nulls`() {
        val json = unavailableReport().toJson()

        json.shouldNotContain("\n")
        json shouldContain "\"alignmentNote\":\"runs are not wave-alignable: no shared sourceId\""

        val reportWithNullNote = fullReport()
        reportWithNullNote.alignmentNote shouldBe null
        reportWithNullNote.toJson() shouldContain "\"alignmentNote\":null"
    }

    @Test
    fun `two toJson calls on equal reports are byte-equal, including independently built equal lists`() {
        val reportOne = fullReport()
        val reportTwo = fullReport().copy(stateDiff = (fullReport().stateDiff as StateDiff.Compared).copy(deltas = comparedDeltas.toList()))

        reportOne shouldBe reportTwo
        reportOne.toJson() shouldBe reportTwo.toJson()
    }

    // --- FidelityDto.of / StateView.of [TTD1-45] -------------------------------------------------

    @Test
    fun `FidelityDto of sorts reasons by name`() {
        FidelityDto.of(Fidelity.Degraded(setOf(Reason.UNKNOWN_DETERMINISM, Reason.VOLATILE_CELL))) shouldBe
            FidelityDto(Verdict.DEGRADED, listOf(Reason.UNKNOWN_DETERMINISM, Reason.VOLATILE_CELL))
        FidelityDto.of(Fidelity.Faithful) shouldBe FidelityDto(Verdict.FAITHFUL, emptyList())
    }

    @Test
    fun `StateView of copies a CellStateView`() {
        val view = civictech.timetravel.reconstruct.CellStateView.of(setOf("b", "a") as java.io.Serializable)

        StateView.of(view) shouldBe StateView("set", "2 rows", listOf(StateViewEntry("0", "a"), StateViewEntry("1", "b")))
    }

    // --- toText [TTD1-45] -------------------------------------------------------------------------

    @Test
    fun `toText contains the record-level and state-level tokens for a Compared report`() {
        val text = fullReport().toText()

        text shouldContain "a.bin"
        text shouldContain "b.bin"
        text shouldContain "alignment: WAVE"
        text shouldContain "aligned prefix: 8"
        text shouldContain "STATE_DIFFERS"
        text shouldContain "#8 wave(...)"
        text shouldContain "11111111-0000-0000-0000-000000000000: 1 rows -> 2 rows"
        text shouldContain "22222222-0000-0000-0000-000000000000: - -> y"
        text shouldContain "33333333-0000-0000-0000-000000000000: - -> -"
        text shouldContain "UNKNOWN_DETERMINISM"
        text shouldContain "allow-list"
    }

    @Test
    fun `toText omits the allow-list line when no FidelityDto is DEGRADED with UNKNOWN_DETERMINISM`() {
        val withoutDegradation = fullReport().copy(
            stateDiff = StateDiff.Compared(
                positionA = 8,
                positionB = 8,
                runA = faithfulFidelity,
                runB = faithfulFidelity,
                deltas = listOf(
                    CellDelta(
                        cellRef = "11111111-0000-0000-0000-000000000000",
                        viewA = null,
                        viewB = null,
                        fidelityA = faithfulFidelity,
                        fidelityB = faithfulFidelity,
                    ),
                ),
            ),
        )

        withoutDegradation.toText().shouldNotContain("UNKNOWN_DETERMINISM")
    }

    @Test
    fun `toText for an Unavailable stateDiff names the run side, reasons and message`() {
        val text = unavailableReport().toText()

        text shouldContain "state diff unavailable for run A"
        text shouldContain "NO_GRAPH_SOURCE"
        text shouldContain "no graph source for run A"
        text shouldContain "state diff unavailable for run B"
        text shouldContain "NOT_STATEFUL"
        text shouldContain "runs are not wave-alignable: no shared sourceId"
    }

    // --- MultiJournalDiffReport --------------------------------------------------------------------

    @Test
    fun `MultiJournalDiffReport toJson and toText render both matched and unmatched journals`() {
        val multi = MultiJournalDiffReport(
            journals = listOf(
                JournalDiff("a.bin", JournalPresence.BOTH, fullReport()),
                JournalDiff("z.bin", JournalPresence.ONLY_IN_B, null),
            ),
        )

        val json = multi.toJson()
        json.shouldNotContain("\n")
        json shouldContain "\"journal\":\"a.bin\""
        json shouldContain "\"journal\":\"z.bin\""
        json shouldContain "\"presence\":\"ONLY_IN_B\""

        val text = multi.toText()
        text shouldContain "a.bin"
        text shouldContain "z.bin"
        text shouldContain "ONLY_IN_B"
    }
}
