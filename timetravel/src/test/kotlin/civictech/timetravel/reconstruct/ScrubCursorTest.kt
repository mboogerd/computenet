package civictech.timetravel.reconstruct

import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.host.ManagedHost
import civictech.timetravel.journal.CheckpointRecord
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalReading
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.junit.jupiter.api.Test

/**
 * TTD1 F5 (`computenet-yhvlz.2`) BS-14, `[TTD1-30]` and `[TTD1-31]` (yhvlz-D9): a [ScrubCursor]
 * advances its one live reconstruction forward in place, restarts from the anchor on a backward
 * request, and every result equals a from-scratch [Reconstructor.stateAt] of the same position as
 * a whole [Reconstruction] data class.
 *
 * Equality alone cannot tell "advanced in place" from "rebuilt": both reach the same state. So
 * [ObservedCursor] records every [openSession] (its anchor and the [ManagedHost] instance it built)
 * and every `close(session)` through the `protected` seams (6tm33-D12), and the tests assert host
 * identity and call counts alongside equality.
 *
 * Recordings are fixed (seeds 14 and 15, fixed scripts), never re-seeded.
 */
class ScrubCursorTest {

    private val singleScript = listOf("a", "b", "c", "d", "e", "f", "g").map { 0 to it }
    private val twoSourceScript = listOf(0 to "a", 1 to "b", 0 to "c", 1 to "d", 0 to "e", 1 to "f", 0 to "g")

    private class Fixture(val reading: JournalReading, val timeline: RunTimeline, val graph: GraphSource) {
        fun cursor() = ObservedCursor(reading, timeline, graph)

        /** The reference: a plain reconstructor, a fresh session per call. */
        fun fromScratch(position: Position): Reconstruction = Reconstructor(reading, timeline, graph).stateAt(position)
    }

    private fun open(recording: DurableGraphFixture.Recording, journal: Journal = recording.journal): Fixture {
        val reading = JournalReader.open(JournalSource.InMemory(journal, "j"))
        return Fixture(reading, RunTimeline.of(reading).getValue("j"), GraphSpecSource(recording.spec))
    }

    /** One source, seven adds, no checkpoint: record `i` is the proxy frame of add `i`. */
    private fun single(): Fixture = open(DurableGraphFixture.record(seed = 14, sourceCount = 1, script = singleScript))

    /** Two sources, checkpoint after the second add: record 0 is the checkpoint (the anchor). */
    private fun checkpointed(): Fixture {
        val fixture = open(DurableGraphFixture.record(seed = 15, sourceCount = 2, script = twoSourceScript, checkpointAfter = 2))
        fixture.timeline.positions[0].record.shouldBeInstanceOf<CheckpointRecord>()
        fixture.timeline.positions[0].isAnchor shouldBe true
        return fixture
    }

    /**
     * Records each [openSession]'s anchor and host, and each `close(session)`'s host, through the
     * `protected` steps — the cursor itself is unchanged.
     */
    private class ObservedCursor(reading: JournalReading, timeline: RunTimeline, graph: GraphSource) :
        ScrubCursor(reading, timeline, graph) {
        val opened = mutableListOf<Pair<Int, ManagedHost>>()
        val closedHosts = mutableListOf<ManagedHost>()

        override fun openSession(anchor: Int): Session =
            super.openSession(anchor).also { opened += anchor to it.host }

        override fun close(session: Session) {
            closedHosts += session.host
            super.close(session)
        }
    }

    private fun anchorFor(timeline: RunTimeline, position: Position): Int {
        val n = timeline.resolve(position)
        return if (n == 0) 0 else timeline.nearestAnchorAtOrBefore(n - 1)
    }

    // -- 1. Forward: one session, fed in place, each step equal to from-scratch. --

    private fun assertForward(fixture: Fixture, p: Int): ObservedCursor {
        withClue("the recording is long enough for p-1 .. p+2") { fixture.timeline.size shouldBeGreaterThan p + 2 }
        val cursor = fixture.cursor()
        cursor.prefixEnd shouldBe null
        for (k in p..p + 2) {
            val position = Position.Index(k)
            withClue("at(Index($k))") {
                cursor.at(position) shouldBe fixture.fromScratch(position)
                cursor.prefixEnd shouldBe k + 1
            }
        }
        withClue("[TTD1-30]: forward requests advance the one live session, never rebuild") {
            cursor.opened.size shouldBe 1
            cursor.opened.single().first shouldBe anchorFor(fixture.timeline, Position.Index(p))
            cursor.closedHosts.shouldBeEmpty()
        }
        return cursor
    }

    @Test
    fun `forward requests feed only the new records to one live host and each equals from-scratch`() {
        for ((name, fixture) in listOf("single" to single(), "checkpointed" to checkpointed())) {
            withClue(name) { assertForward(fixture, p = 3).close() }
        }
    }

    // -- 2. Backward: close, restart from the anchor with a fresh host, then advance again. --

    @Test
    fun `a backward request closes the live session and restarts from the anchor, then advances in place again`() {
        for ((name, fixture) in listOf("single" to single(), "checkpointed" to checkpointed())) {
            withClue(name) {
                val p = 3
                val cursor = assertForward(fixture, p)
                val firstHost = cursor.opened.single().second

                val back = Position.Index(p - 1)
                cursor.at(back) shouldBe fixture.fromScratch(back)
                withClue("[TTD1-31]: restart from nearestAnchorAtOrBefore(n' - 1) with a fresh host") {
                    cursor.opened.size shouldBe 2
                    val (anchor, host) = cursor.opened[1]
                    anchor shouldBe anchorFor(fixture.timeline, back)
                    anchor shouldBe 0 // in the landed format the only anchor is record 0 (F2 D2)
                    host shouldNotBeSameInstanceAs firstHost
                    cursor.closedHosts shouldContainExactly listOf(firstHost)
                }
                cursor.prefixEnd shouldBe p

                val forward = Position.Index(p)
                cursor.at(forward) shouldBe fixture.fromScratch(forward)
                withClue("the restarted session advances in place: no third openSession") {
                    cursor.opened.size shouldBe 2
                    cursor.closedHosts.size shouldBe 1
                }
                cursor.close()
            }
        }
    }

    // -- 3. Same Position is cached; another Position at the same n re-observes. --

    @Test
    fun `the same position returns the cached instance and a cut at the same prefix re-observes without replay`() {
        val fixture = single()
        val cursor = fixture.cursor()
        val last = Position.Index(fixture.timeline.size - 1)

        val first = cursor.at(last)
        cursor.at(last) shouldBeSameInstanceAs first

        // This fixture journals only contextless proxy frames, so no position is waved and every
        // cut resolves to the whole journal — the same prefix end as `last`.
        val cut = Position.Cut(emptyMap())
        fixture.timeline.resolve(cut) shouldBe fixture.timeline.size

        val reobserved = cursor.at(cut)
        reobserved shouldBe fixture.fromScratch(cut)
        reobserved.position.requested shouldBe cut
        reobserved shouldNotBeSameInstanceAs first
        withClue("re-observing replays nothing and rebuilds nothing") {
            cursor.opened.size shouldBe 1
            cursor.closedHosts.shouldBeEmpty()
        }
        cursor.at(cut) shouldBeSameInstanceAs reobserved
        cursor.close()
    }

    // -- 4. close(): shuts the live session down once, is idempotent, and forbids at. --

    @Test
    fun `close shuts the live session down once, a second close is a no-op, and at afterwards throws`() {
        val fixture = single()
        val cursor = fixture.cursor()
        cursor.at(Position.Index(2))
        val host = cursor.opened.single().second
        cursor.prefixEnd shouldBe 3

        cursor.close()
        cursor.closedHosts shouldContainExactly listOf(host)
        cursor.prefixEnd shouldBe null

        cursor.close()
        cursor.closedHosts shouldContainExactly listOf(host)

        shouldThrow<IllegalStateException> { cursor.at(Position.Index(2)) }
        shouldThrow<IllegalStateException> { cursor.at(Position.Index(4)) }
        cursor.opened.size shouldBe 1
    }

    // -- 5. An incomplete forward step reports from-scratch's detail, and is never advanced. --

    /**
     * The single-source recording with record `j = 2` (the proxy frame of the third add) replaced
     * by bytes that are a frame by type but not JSON (ReconstructionShapeTest's injection), so the
     * kernel's `recoverFrom` throws `RecoveryIncomplete` at it.
     *
     * The forward step goes to `last - 1`, not `last`, so that the following request at `last` is
     * itself forward of the live prefix: only the after-incomplete rule, not the backward rule,
     * can make it restart.
     */
    @Test
    fun `an incomplete forward step equals from-scratch and the next request restarts instead of advancing`() {
        val recording = DurableGraphFixture.record(seed = 14, sourceCount = 1, script = singleScript)
        val j = recording.steps[2].proxyIndex
        val records = recording.journal.replay().toMutableList()
        records[j] = byteArrayOf(1) + "{not json".encodeToByteArray()
        val fixture = open(recording, InMemoryJournal().apply { reset(records) })
        val last = fixture.timeline.size - 1
        withClue("j is mid-journal") {
            j shouldBeGreaterThanOrEqual 1
            last - 1 shouldBeGreaterThan j
        }
        val cursor = fixture.cursor()

        val before = Position.Index(j - 1)
        val complete = cursor.at(before)
        complete shouldBe fixture.fromScratch(before)
        complete.details.shouldBeEmpty()

        val step = Position.Index(last - 1)
        val incomplete = cursor.at(step)
        val scratch = fixture.fromScratch(step)
        incomplete shouldBe scratch
        withClue("the step's detail is normalized to the records fed to this session") {
            val detail = incomplete.details.single().shouldBeInstanceOf<RecoveryIncomplete>()
            detail.recordIndex shouldBe j
            detail.total shouldBe last - incomplete.position.anchor
            detail shouldBe scratch.details.single()
        }
        cursor.opened.size shouldBe 1
        cursor.at(step) shouldBeSameInstanceAs incomplete

        val onward = Position.Index(last)
        cursor.at(onward) shouldBe fixture.fromScratch(onward)
        withClue("a partially fed host is never advanced: the forward request restarts") {
            cursor.opened.size shouldBe 2
            cursor.closedHosts shouldContainExactly listOf(cursor.opened[0].second)
        }
        cursor.close()
    }
}
