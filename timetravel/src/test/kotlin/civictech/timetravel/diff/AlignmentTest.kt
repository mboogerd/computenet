package civictech.timetravel.diff

import civictech.cell.CellRef
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.data.SetOps
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.port.PortRef
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.WireCodec
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.journal.FrameRecord
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.journal.MalformedRecord
import civictech.timetravel.timeline.RunTimeline
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * TTD1 F6 (computenet-si0tl.1): record alignment over hand-built journals — keys and mode
 * (`si0tl-D7`, `[TTD1-40]`, `[TTD1-43]`), equality and the aligned prefix (`si0tl-D8`,
 * `[TTD1-39]`), first-divergence classification (`si0tl-D9`, `[TTD1-41]`), and canonical args (D4).
 */
class AlignmentTest {

    private val ref = CellRef(UUID(1, 1))
    private val a: UUID = UUID(0xA, 0xA)
    private val b: UUID = UUID(0xB, 0xB)
    private val c: UUID = UUID(0xC, 0xC)

    /** One journal entry: a waved frame (`source`, `counter`) or raw bytes. */
    private sealed interface Entry
    private data class Waved(val source: UUID, val counter: Long) : Entry
    private class Raw(val bytes: ByteArray) : Entry

    private fun w(source: UUID, counter: Long): Entry = Waved(source, counter)

    /** `si0tl-D13`'s waved recipe (`FrontierCutResolutionTest.appendFrame`). */
    private fun appendWaved(journal: Journal, source: UUID, counter: Long) {
        val context = MessageContext(Timestamp(source, counter), PortRef.generate(ref))
        val invocation = HostedPortInvocation(
            ref, "inlet", HostedPortInvocation.Type.PORT_API,
            Invocation.of(SetOps::class.java.getMethod("add", Any::class.java), arrayOf("x"), context),
        )
        journal.append(byteArrayOf(1) + WireCodec.encode(invocation))
    }

    /** `si0tl-D13`'s descriptor-less recipe: a hand-written contextless frame. */
    private fun contextless(args: String = """["x"]""", methodId: Long = 1, contractId: Long = 999): Entry = Raw(
        byteArrayOf(1) +
            ("""{"cellRef":{"id":"${ref.id}"},"portName":"inlet","type":"PORT_API","contractId":$contractId,""" +
                """"methodId":$methodId,"context":null,"args":$args}""").encodeToByteArray(),
    )

    private fun timeline(vararg entries: Entry): RunTimeline {
        val journal = InMemoryJournal()
        for (entry in entries) {
            when (entry) {
                is Waved -> appendWaved(journal, entry.source, entry.counter)
                is Raw -> journal.append(entry.bytes)
            }
        }
        val records = JournalReader.open(JournalSource.InMemory(journal, "j")).records.toList()
        return RunTimeline("j", records)
    }

    /** A1 A2 B1 A3 B2 A4 B3. */
    private fun interleaved() =
        timeline(w(a, 1), w(a, 2), w(b, 1), w(a, 3), w(b, 2), w(a, 4), w(b, 3))

    private fun contextlessTimeline() = timeline(contextless(), contextless(), contextless())

    private fun wave(source: UUID, counter: Long) = RecordKey.Wave(source, counter, ref, "inlet")

    // --- [TTD1-39] aligned prefix and first divergent index -------------------------------------

    @Test
    fun `TTD1-39 an extra trailing record in B is ONLY_IN_B at the aligned prefix`() {
        val alignment = RecordAlignment.align(
            timeline(w(a, 1), w(a, 2), w(b, 1), w(a, 3)),
            timeline(w(a, 1), w(a, 2), w(b, 1), w(a, 3), w(b, 2)),
        )

        alignment shouldBe Alignment(
            AlignmentMode.WAVE, null, 4,
            RecordDivergence(DivergenceClass.ONLY_IN_B, null, 4, null, wave(b, 2), null, null),
        )
    }

    @Test
    fun `a run aligns fully with itself`() {
        val interleaved = interleaved()
        RecordAlignment.align(interleaved, interleaved) shouldBe Alignment(AlignmentMode.WAVE, null, 7, null)

        val contextless = contextlessTimeline()
        RecordAlignment.align(contextless, contextless) shouldBe Alignment(AlignmentMode.ORDINAL, null, 3, null)
    }

    // --- [TTD1-40] keys ---------------------------------------------------------------------------

    @Test
    fun `TTD1-40 waved frames key by wave coordinate`() {
        RecordAlignment.keys(interleaved(), AlignmentMode.WAVE) shouldBe listOf(
            wave(a, 1), wave(a, 2), wave(b, 1), wave(a, 3), wave(b, 2), wave(a, 4), wave(b, 3),
        )
    }

    @Test
    fun `TTD1-40 contextless frames key by ordinal within cellRef and port`() {
        RecordAlignment.keys(contextlessTimeline(), AlignmentMode.ORDINAL) shouldBe listOf(
            RecordKey.Ordinal("frame", ref, "inlet", 0),
            RecordKey.Ordinal("frame", ref, "inlet", 1),
            RecordKey.Ordinal("frame", ref, "inlet", 2),
        )
    }

    @Test
    fun `TTD1-40 a malformed record keys by its own kind, the frames around it keep their ordinals`() {
        val t = timeline(contextless(), Raw(byteArrayOf(1) + "{not json".encodeToByteArray()), contextless())
        t.positions[1].record.shouldBeInstanceOf<MalformedRecord>()

        RecordAlignment.keys(t, AlignmentMode.ORDINAL) shouldBe listOf(
            RecordKey.Ordinal("frame", ref, "inlet", 0),
            RecordKey.Ordinal("MalformedRecord", null, null, 0),
            RecordKey.Ordinal("frame", ref, "inlet", 1),
        )
    }

    @Test
    fun `TTD1-40 under INDEX_ONLY every position keys by its index`() {
        RecordAlignment.keys(interleaved(), AlignmentMode.INDEX_ONLY) shouldBe (0 until 7).map { RecordKey.Index(it) }
    }

    @Test
    fun `si0tl-D13 key rendering`() {
        wave(a, 3).render() shouldBe "wave(00000000, 3) 00000000.inlet"
        RecordKey.Ordinal("frame", ref, "inlet", 2).render() shouldBe "frame 00000000.inlet #2"
        RecordKey.Ordinal("MalformedRecord", null, null, 0).render() shouldBe "MalformedRecord -.- #0"
        RecordKey.Index(5).render() shouldBe "#5"
    }

    @Test
    fun `si0tl-D13 key rendering takes the sourceId and the cellRef from their own fields`() {
        // Every fixture UUID above renders as "00000000", so it cannot tell the two fields apart.
        val source = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000000")
        val cell = CellRef(UUID.fromString("11111111-0000-0000-0000-000000000000"))

        RecordKey.Wave(source, 3, cell, "inlet").render() shouldBe "wave(aaaaaaaa, 3) 11111111.inlet"
        RecordKey.Ordinal("frame", cell, "inlet", 2).render() shouldBe "frame 11111111.inlet #2"
    }

    // --- [TTD1-43] mode selection -----------------------------------------------------------------

    @Test
    fun `TTD1-43 mode is INDEX_ONLY iff both source sets are non-empty and disjoint`() {
        val waveA = timeline(w(a, 1), w(a, 2))
        val waveC = timeline(w(c, 1), w(c, 2))

        RecordAlignment.mode(waveA, waveC) shouldBe AlignmentMode.INDEX_ONLY
        RecordAlignment.align(waveA, waveC).note shouldBe RecordAlignment.NOT_WAVE_ALIGNABLE
        RecordAlignment.NOT_WAVE_ALIGNABLE shouldBe "runs are not wave-alignable: no shared sourceId"

        RecordAlignment.mode(waveA, timeline(w(a, 1))) shouldBe AlignmentMode.WAVE

        RecordAlignment.mode(contextlessTimeline(), contextlessTimeline()) shouldBe AlignmentMode.ORDINAL
        RecordAlignment.align(contextlessTimeline(), contextlessTimeline()).note shouldBe null

        RecordAlignment.mode(waveA, contextlessTimeline()) shouldBe AlignmentMode.ORDINAL
    }

    @Test
    fun `under INDEX_ONLY equality ignores the frame context`() {
        val runA = timeline(w(a, 1), w(a, 2), w(a, 3))

        RecordAlignment.align(runA, timeline(w(c, 1), w(c, 2), w(c, 3))) shouldBe
            Alignment(AlignmentMode.INDEX_ONLY, RecordAlignment.NOT_WAVE_ALIGNABLE, 3, null)

        val longer = RecordAlignment.align(runA, timeline(w(c, 1), w(c, 2), w(c, 3), w(c, 4)))
        longer.alignedPrefix shouldBe 3
        longer.divergence!!.kind shouldBe DivergenceClass.ONLY_IN_B
        longer.divergence!!.indexB shouldBe 3
        longer.divergence!!.indexA shouldBe null
    }

    // --- [TTD1-41] one hand-built pair per record-level class -------------------------------------

    @Test
    fun `TTD1-41 ONLY_IN_A - A has an extra record before a shared one`() {
        val d = RecordAlignment.align(
            timeline(w(a, 1), w(a, 2), w(a, 3), w(b, 1)),
            timeline(w(a, 1), w(a, 2), w(b, 1)),
        ).divergence!!

        d.kind shouldBe DivergenceClass.ONLY_IN_A
        d.indexA shouldBe 2
        d.indexB shouldBe 2
        d.keyA shouldBe wave(a, 3)
    }

    @Test
    fun `TTD1-41 ONLY_IN_B - the mirror`() {
        val d = RecordAlignment.align(
            timeline(w(a, 1), w(a, 2), w(b, 1)),
            timeline(w(a, 1), w(a, 2), w(a, 3), w(b, 1)),
        ).divergence!!

        d.kind shouldBe DivergenceClass.ONLY_IN_B
        d.indexA shouldBe 2
        d.indexB shouldBe 2
        d.keyB shouldBe wave(a, 3)
    }

    @Test
    fun `si0tl-D9 step 3 - when neither key occurs later in the other run, ONLY_IN_B takes precedence`() {
        val d = RecordAlignment.align(
            timeline(w(a, 1), w(a, 2)),
            timeline(w(a, 1), w(b, 1)),
        ).divergence!!

        d.kind shouldBe DivergenceClass.ONLY_IN_B
        d.indexA shouldBe 1
        d.indexB shouldBe 1
        d.keyA shouldBe wave(a, 2)
        d.keyB shouldBe wave(b, 1)
    }

    @Test
    fun `TTD1-41 FRAME_DIFFERS - same key, different methodId`() {
        val alignment = RecordAlignment.align(timeline(contextless(methodId = 1)), timeline(contextless(methodId = 2)))
        val d = alignment.divergence!!

        alignment.alignedPrefix shouldBe 0
        d.kind shouldBe DivergenceClass.FRAME_DIFFERS
        d.keyA shouldBe d.keyB
        d.detailA shouldNotBe null
        d.detailB shouldNotBe null
        d.detailA shouldNotBe d.detailB
    }

    @Test
    fun `TTD1-41 ARGS_DIFFER - same key, different args, reached without descriptors`() {
        val runA = timeline(contextless(args = """["x"]"""))
        val runB = timeline(contextless(args = """["y"]"""))
        listOf(runA, runB).forEach {
            it.positions[0].record.shouldBeInstanceOf<FrameRecord>().reasons shouldContain Reason.NO_DESCRIPTOR
        }

        val d = RecordAlignment.align(runA, runB).divergence!!

        d.kind shouldBe DivergenceClass.ARGS_DIFFER
        d.indexA shouldBe 0
        d.indexB shouldBe 0
        d.detailA shouldBe "[\"x\"]"
        d.detailB shouldBe "[\"y\"]"
    }

    @Test
    fun `TTD1-41 WAVE_ORDER_DIFFERS - both keys occur later in the other run`() {
        val d = RecordAlignment.align(
            timeline(w(a, 1), w(b, 1), w(a, 2)),
            timeline(w(a, 1), w(a, 2), w(b, 1)),
        ).divergence!!

        d.kind shouldBe DivergenceClass.WAVE_ORDER_DIFFERS
        d.indexA shouldBe 1
        d.indexB shouldBe 1
        d.keyA shouldBe wave(b, 1)
        d.keyB shouldBe wave(a, 2)
    }

    // --- D4 canonical args ------------------------------------------------------------------------

    @Test
    fun `args differing only in object-key order align equal`() {
        val runA = timeline(contextless(args = """[{"b":1,"a":2}]"""))
        val runB = timeline(contextless(args = """[{"a":2,"b":1}]"""))
        // Guard against a vacuous pass: both sides must be frames whose raw args really differ in order.
        val argsA = runA.positions[0].record.shouldBeInstanceOf<FrameRecord>().args
        val argsB = runB.positions[0].record.shouldBeInstanceOf<FrameRecord>().args
        argsA.toString() shouldNotBe argsB.toString()

        val alignment = RecordAlignment.align(runA, runB)

        alignment.divergence shouldBe null
        alignment.alignedPrefix shouldBe 1
    }

    @Test
    fun `canonical sorts object keys recursively and keeps array order and primitives`() {
        val element = Json.parseToJsonElement("""{"b":[{"d":1,"c":null}],"a":"x"}""")

        CanonicalJson.canonical(element) shouldBe """{"a":"x","b":[{"c":null,"d":1}]}"""
        CanonicalJson.canonical(kotlinx.serialization.json.JsonNull) shouldBe "null"
        CanonicalJson.canonical(Json.parseToJsonElement("""["1",1]""")) shouldBe """["1",1]"""
    }
}
