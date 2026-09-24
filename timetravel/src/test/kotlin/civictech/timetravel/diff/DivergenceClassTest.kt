package civictech.timetravel.diff

import civictech.cell.CellRef
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
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
import civictech.timetravel.journal.JournalReading
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.reconstruct.CellStateView
import civictech.timetravel.reconstruct.DurableGraphFixture
import civictech.timetravel.reconstruct.GraphBuild
import civictech.timetravel.reconstruct.GraphSource
import civictech.timetravel.reconstruct.GraphSpecSource
import civictech.timetravel.reconstruct.Reconstructor
import civictech.timetravel.timeline.RunTimeline
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * TTD1 F6 (computenet-si0tl.3) `[TTD1-41]` through the report: each of the six divergence classes is
 * reached by one pair and reported as `RunDiff.diff(...).divergence!!.kind` — the five record-level
 * classes over hand-built journals (`si0tl-D9`), `STATE_DIFFERS` over one recording diffed against
 * itself with an extra cell in run B's graph (`si0tl-D10`).
 *
 * The record-level cases also pin `si0tl-D10`'s "no host for nothing": the side WITH a reconstructor
 * builds over a [GraphSource] that throws if asked, and the other side's reconstructor is `null`, so
 * `diff` must not reconstruct either run.
 */
class DivergenceClassTest {

    private val ref = CellRef(UUID(1, 1))
    private val a: UUID = UUID(0xA, 0xA)
    private val b: UUID = UUID(0xB, 0xB)

    private sealed interface Entry
    private data class Waved(val source: UUID, val counter: Long) : Entry
    private class Raw(val bytes: ByteArray) : Entry

    private fun w(source: UUID, counter: Long): Entry = Waved(source, counter)

    /** `si0tl-D13`'s descriptor-less recipe: a hand-written contextless frame. */
    private fun contextless(args: String = """["x"]""", methodId: Long = 1): Entry = Raw(
        byteArrayOf(1) +
            ("""{"cellRef":{"id":"${ref.id}"},"portName":"inlet","type":"PORT_API","contractId":999,""" +
                """"methodId":$methodId,"context":null,"args":$args}""").encodeToByteArray(),
    )

    private class Run(val reading: JournalReading, val timeline: RunTimeline)

    private fun run(label: String, vararg entries: Entry): Run {
        val journal: Journal = InMemoryJournal()
        for (entry in entries) {
            when (entry) {
                is Waved -> {
                    val context = MessageContext(Timestamp(entry.source, entry.counter), PortRef.generate(ref))
                    val invocation = HostedPortInvocation(
                        ref, "inlet", HostedPortInvocation.Type.PORT_API,
                        Invocation.of(SetOps::class.java.getMethod("add", Any::class.java), arrayOf("x"), context),
                    )
                    journal.append(byteArrayOf(1) + WireCodec.encode(invocation))
                }

                is Raw -> journal.append(entry.bytes)
            }
        }
        val reading = JournalReader.open(JournalSource.InMemory(journal, label))
        return Run(reading, RunTimeline.of(reading).getValue(label))
    }

    private val mustNotBeBuilt = GraphSource { error("must not be built") }

    /**
     * The record-level report of [runA] vs [runB], asserted identical whichever side carries a
     * reconstructor over [mustNotBeBuilt] — a `null` other side means no host is built at all.
     */
    private fun recordLevel(runA: Run, runB: Run): RunDiffReport {
        val plain = RunDiff.diff(runA.timeline, runB.timeline)
        val onlyB = RunDiff.diff(
            runA.timeline, runB.timeline,
            null, Reconstructor(runB.reading, runB.timeline, mustNotBeBuilt),
        )
        val onlyA = RunDiff.diff(
            runA.timeline, runB.timeline,
            Reconstructor(runA.reading, runA.timeline, mustNotBeBuilt), null,
        )
        onlyB.copy(stateDiff = plain.stateDiff) shouldBe plain
        onlyA.copy(stateDiff = plain.stateDiff) shouldBe plain
        onlyB.stateDiff shouldBe StateDiff.Unavailable(
            listOf(RunUnavailable(RunSide.A, listOf(Reason.NO_GRAPH_SOURCE), Reconstructor.NO_GRAPH_SOURCE_MESSAGE)),
        )
        onlyA.stateDiff shouldBe StateDiff.Unavailable(
            listOf(RunUnavailable(RunSide.B, listOf(Reason.NO_GRAPH_SOURCE), Reconstructor.NO_GRAPH_SOURCE_MESSAGE)),
        )
        plain.stateDiff.shouldBeInstanceOf<StateDiff.Unavailable>().runs.map { it.run } shouldBe
            listOf(RunSide.A, RunSide.B)
        (plain.journalA to plain.journalB) shouldBe ("a" to "b")
        return plain
    }

    @Test
    fun `ONLY_IN_A - A has an extra record before a shared one`() {
        val report = recordLevel(run("a", w(a, 1), w(a, 2), w(a, 3), w(b, 1)), run("b", w(a, 1), w(a, 2), w(b, 1)))
        val d = report.divergence!!

        d.kind shouldBe DivergenceClass.ONLY_IN_A
        report.alignedPrefix shouldBe 2
        d.indexA shouldBe 2
        d.indexB shouldBe 2
        d.keyA shouldBe RecordKey.Wave(a, 3, ref, "inlet").render()
        d.labelA shouldBe run("a", w(a, 1), w(a, 2), w(a, 3), w(b, 1)).timeline.labels(2)
    }

    @Test
    fun `ONLY_IN_B - the mirror`() {
        val d = recordLevel(run("a", w(a, 1), w(a, 2), w(b, 1)), run("b", w(a, 1), w(a, 2), w(a, 3), w(b, 1)))
            .divergence!!

        d.kind shouldBe DivergenceClass.ONLY_IN_B
        d.indexA shouldBe 2
        d.indexB shouldBe 2
        d.keyB shouldBe RecordKey.Wave(a, 3, ref, "inlet").render()
    }

    @Test
    fun `FRAME_DIFFERS - same key, different methodId`() {
        val d = recordLevel(run("a", contextless(methodId = 1)), run("b", contextless(methodId = 2))).divergence!!

        d.kind shouldBe DivergenceClass.FRAME_DIFFERS
        d.keyA shouldBe d.keyB
        d.detailA shouldNotBe d.detailB
    }

    @Test
    fun `ARGS_DIFFER - same key, different args, reached without a descriptor`() {
        val runA = run("a", contextless(args = """["x"]"""))
        val runB = run("b", contextless(args = """["y"]"""))
        listOf(runA, runB).forEach {
            it.timeline.positions[0].record.shouldBeInstanceOf<FrameRecord>().reasons shouldContain Reason.NO_DESCRIPTOR
        }

        val d = recordLevel(runA, runB).divergence!!

        d.kind shouldBe DivergenceClass.ARGS_DIFFER
        d.indexA shouldBe 0
        d.indexB shouldBe 0
        d.detailA shouldBe "[\"x\"]"
        d.detailB shouldBe "[\"y\"]"
    }

    @Test
    fun `WAVE_ORDER_DIFFERS - both keys at the divergence occur later in the other run`() {
        val d = recordLevel(run("a", w(a, 1), w(b, 1), w(a, 2)), run("b", w(a, 1), w(a, 2), w(b, 1))).divergence!!

        d.kind shouldBe DivergenceClass.WAVE_ORDER_DIFFERS
        d.indexA shouldBe 1
        d.indexB shouldBe 1
    }

    @Test
    fun `STATE_DIFFERS - one recording against itself, run B's graph holding one extra cell`() {
        val recording = DurableGraphFixture.record(seed = 11, sourceCount = 1, script = listOf(0 to "a", 0 to "b", 0 to "c"))
        val reading = JournalReader.open(JournalSource.InMemory(recording.journal, "j"))
        val t = RunTimeline.of(reading).getValue("j")
        val extraRef = CellRef(UUID(11, 999))
        val withExtra = GraphSource { host ->
            val base = GraphSpecSource(recording.spec).build(host)
            val extra = SetCell<String>(extraRef)
            host.managementInlet.call.spawn(extra)
            GraphBuild(base.cells + extra)
        }

        val report = RunDiff.diff(
            t, t,
            Reconstructor(reading, t, GraphSpecSource(recording.spec)),
            Reconstructor(reading, t, withExtra),
        )

        t.size shouldBe 3
        (report.journalA to report.journalB) shouldBe ("j" to "j")
        report.alignedPrefix shouldBe 3
        val d = report.divergence!!
        d.kind shouldBe DivergenceClass.STATE_DIFFERS
        d.indexA shouldBe 2
        d.indexB shouldBe 2
        d.labelA shouldBe t.labels(2)
        d.keyA shouldBe null
        d.keyB shouldBe null

        // The breakdown's `unverified:` literal StateView("set", "0 rows", []) does not hold: a
        // SetCell snapshot is a map (adds/dels/counter/...), so the view is read from the cell.
        val emptyView = StateView.of(CellStateView.of(SetCell<String>(extraRef).snapshot()))
        val faithful = FidelityDto(Verdict.FAITHFUL, emptyList())
        val compared = report.stateDiff.shouldBeInstanceOf<StateDiff.Compared>()
        compared shouldBe StateDiff.Compared(
            2, 2, faithful, faithful,
            listOf(CellDelta(extraRef.id.toString(), null, emptyView, null, faithful)),
        )
    }
}
