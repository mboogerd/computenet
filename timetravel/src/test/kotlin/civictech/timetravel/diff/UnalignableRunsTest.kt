package civictech.timetravel.diff

import civictech.cell.CellRef
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.data.SetOps
import civictech.cell.durability.InMemoryJournal
import civictech.cell.port.PortRef
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.WireCodec
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.timeline.RunTimeline
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * TTD1 F6 (computenet-si0tl.3) BS-13, `[TTD1-43]` report half: two runs whose waves share no
 * `sourceId` are aligned by index, and the report — JSON field and text — says so.
 */
class UnalignableRunsTest {

    private val ref = CellRef(UUID(1, 1))
    private val epochA: UUID = UUID(0xA, 0xA)
    private val epochC: UUID = UUID(0xC, 0xC)

    /** `si0tl-D13`'s waved recipe: one `add("x")` frame per counter, under [source]. */
    private fun waved(label: String, source: UUID, counters: List<Long>): RunTimeline {
        val journal = InMemoryJournal()
        for (counter in counters) {
            val context = MessageContext(Timestamp(source, counter), PortRef.generate(ref))
            val invocation = HostedPortInvocation(
                ref, "inlet", HostedPortInvocation.Type.PORT_API,
                Invocation.of(SetOps::class.java.getMethod("add", Any::class.java), arrayOf("x"), context),
            )
            journal.append(byteArrayOf(1) + WireCodec.encode(invocation))
        }
        return RunTimeline.of(JournalReader.open(JournalSource.InMemory(journal, label))).getValue(label)
    }

    @Test
    fun `BS-13 identical frames under disjoint epochs align by index, fully, and the report says so`() {
        val report = RunDiff.diff(waved("a", epochA, listOf(1, 2, 3)), waved("b", epochC, listOf(1, 2, 3)))

        report.alignment shouldBe AlignmentMode.INDEX_ONLY
        report.alignmentNote shouldBe RecordAlignment.NOT_WAVE_ALIGNABLE
        report.alignmentNote shouldBe "runs are not wave-alignable: no shared sourceId"
        report.alignedPrefix shouldBe 3
        report.sizeA shouldBe 3
        report.sizeB shouldBe 3
        (report.journalA to report.journalB) shouldBe ("a" to "b")
        report.divergence shouldBe null

        val text = report.toText()
        text shouldContain "runs are not wave-alignable: no shared sourceId"
        text shouldContain "alignment: INDEX_ONLY"
        text shouldNotContain "alignment: WAVE"
    }

    @Test
    fun `BS-13 one more record in B is ONLY_IN_B at index sizeA, keyed by index`() {
        val report = RunDiff.diff(waved("a", epochA, listOf(1, 2, 3)), waved("b", epochC, listOf(1, 2, 3, 4)))

        report.alignment shouldBe AlignmentMode.INDEX_ONLY
        report.alignmentNote shouldBe RecordAlignment.NOT_WAVE_ALIGNABLE
        report.alignedPrefix shouldBe 3
        val d = report.divergence!!
        d.kind shouldBe DivergenceClass.ONLY_IN_B
        d.indexA shouldBe null
        d.indexB shouldBe 3
        d.keyA shouldBe null
        d.keyB shouldBe "#3"
        d.labelA shouldBe null
        d.labelB!! shouldContain "#3"
        report.toText() shouldNotContain "alignment: WAVE"
    }
}
