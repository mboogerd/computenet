package civictech.timetravel.timeline

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
import civictech.timetravel.journal.FrameRecord
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * TTD1 F2 (computenet-56io0.1): [RunTimeline.resolve] on [Position.Cut] over a hand-built
 * two-source interleaving — feature D3, [TTD1-16], [TTD1-18], `frontierAt`, `sources`, and the
 * waved-frame `labels` tokens.
 */
class FrontierCutResolutionTest {

    private val ref = CellRef(UUID(1, 1))
    private val sourceA: UUID = UUID(0xA, 0xA)
    private val sourceB: UUID = UUID(0xB, 0xB)

    /** One journaled type-1 frame, waved by ([source], [counter]) — the recipe
     * `JournalReaderTest`'s "D4 the structural tuple…" test already uses. */
    private fun appendFrame(journal: Journal, source: UUID, counter: Long) {
        val context = MessageContext(Timestamp(source, counter), PortRef.generate(ref))
        val invocation = HostedPortInvocation(
            ref, "inlet", HostedPortInvocation.Type.PORT_API,
            Invocation.of(SetOps::class.java.getMethod("add", Any::class.java), arrayOf("x"), context),
        )
        journal.append(byteArrayOf(1) + WireCodec.encode(invocation))
    }

    /** A1 A2 B1 A3 B2 A4 B3, read back through [JournalReader], as a [RunTimeline]. */
    private fun interleavedTimeline(): RunTimeline {
        val journal = InMemoryJournal()
        appendFrame(journal, sourceA, 1)
        appendFrame(journal, sourceA, 2)
        appendFrame(journal, sourceB, 1)
        appendFrame(journal, sourceA, 3)
        appendFrame(journal, sourceB, 2)
        appendFrame(journal, sourceA, 4)
        appendFrame(journal, sourceB, 3)
        val records = JournalReader.open(JournalSource.InMemory(journal, "j")).records.toList()
        return RunTimeline("j", records)
    }

    @Test
    fun `TTD1-16 per-source counters are strictly increasing along journal order, sources is A and B`() {
        val timeline = interleavedTimeline()

        val byA = timeline.positions.mapNotNull { it.wave }.filter { it.sourceId == sourceA }.map { it.counter }
        val byB = timeline.positions.mapNotNull { it.wave }.filter { it.sourceId == sourceB }.map { it.counter }
        byA shouldBe listOf(1L, 2L, 3L, 4L)
        byB shouldBe listOf(1L, 2L, 3L)
        timeline.sources shouldBe setOf(sourceA, sourceB)
    }

    @Test
    fun `TTD1-18 resolve Cut returns the longest prefix with no beyond-cut waved frame`() {
        val timeline = interleavedTimeline()

        timeline.resolve(Position.Cut(mapOf(sourceA to 3L, sourceB to 1L))) shouldBe 4
        timeline.resolve(Position.Cut(mapOf(sourceA to 4L, sourceB to 1L))) shouldBe 4
        timeline.resolve(Position.Cut(mapOf(sourceA to 3L))) shouldBe 2
        timeline.resolve(Position.Cut(emptyMap())) shouldBe 0
        timeline.resolve(Position.Cut(mapOf(sourceA to 9L, sourceB to 9L))) shouldBe 7
    }

    @Test
    fun `frontierAt and the resolve-of-frontierAt round trip`() {
        val timeline = interleavedTimeline()

        timeline.frontierAt(3) shouldBe mapOf(sourceA to 3L, sourceB to 1L)
        timeline.frontierAt(5) shouldBe mapOf(sourceA to 4L, sourceB to 2L)
        for (k in 0 until timeline.size) {
            (timeline.resolve(Position.Cut(timeline.frontierAt(k))) >= k + 1) shouldBe true
        }
    }

    @Test
    fun `no checkpoint means no anchor, nearestAnchorAtOrBefore is always 0`() {
        val timeline = interleavedTimeline()

        timeline.positions.forEach { it.isAnchor shouldBe false }
        for (k in 0 until timeline.size) {
            timeline.nearestAnchorAtOrBefore(k) shouldBe 0
        }
    }

    @Test
    fun `56io0-D4 labels of a waved frame carries index, source prefix, wave and port`() {
        val timeline = interleavedTimeline()

        val label = timeline.labels(3) // A3
        label shouldContain "#3"
        label shouldContain sourceA.toString().take(8)
        label shouldContain "wave 3"
        label shouldContain "inlet"
    }

    @Test
    fun `every frame in this fixture is a FrameRecord`() {
        val timeline = interleavedTimeline()
        timeline.positions.forEach { (it.record is FrameRecord) shouldBe true }
    }
}
