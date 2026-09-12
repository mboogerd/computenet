package civictech.demo.allocatorobserve.view

import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.demo.allocatorobserve.SpendRecord
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

private const val TOLERANCE = 1e-9

/** A single fixed tag source for this test file — only the counter needs to vary. */
private val SOURCE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

private fun tag(counter: Long): Timestamp = Timestamp(SOURCE, counter)

private fun record(project: String, started: String, ended: String, workItem: String = "w"): SpendRecord =
    SpendRecord(v = 1, project = project, machine = "m1", workItem = workItem, started = started, ended = ended)

/** A [SetDelta] that adds [record] under one fresh [tag]. */
private fun addDelta(record: SpendRecord, counter: Long): SetDelta<SpendRecord> =
    SetDelta(adds = mapOf(record to setOf(tag(counter))))

/** A [SetDelta] that dels [record], observing exactly [counters]. */
private fun delDelta(record: SpendRecord, vararg counters: Long): SetDelta<SpendRecord> =
    SetDelta(dels = mapOf(record to counters.map(::tag).toSet()))

class SessionLedgerTest {

    // --- sessionOf: total over SpendRecord (fpml.3-D6) ---

    @Test
    fun `sessionOf is Valid for valid ISO instants`() {
        val r = record("CN", "2026-04-01T09:00:00Z", "2026-04-01T11:00:00Z")
        val parse = sessionOf(r)
        parse shouldBe SessionParse.Valid(
            SpendSession(
                "CN",
                Instant.parse("2026-04-01T09:00:00Z"),
                Instant.parse("2026-04-01T11:00:00Z"),
                r,
            ),
        )
    }

    @Test
    fun `sessionOf is Unattributable UNPARSEABLE_STARTED for a garbled started string`() {
        val r = record("CN", "not-a-time", "2026-04-01T11:00:00Z")
        sessionOf(r) shouldBe SessionParse.Unattributable(UnattributableReason.UNPARSEABLE_STARTED)
    }

    @Test
    fun `sessionOf is Unattributable UNPARSEABLE_ENDED for a garbled ended string`() {
        val r = record("CN", "2026-04-01T09:00:00Z", "not-a-time")
        sessionOf(r) shouldBe SessionParse.Unattributable(UnattributableReason.UNPARSEABLE_ENDED)
    }

    @Test
    fun `sessionOf is Unattributable ENDED_BEFORE_STARTED when ended precedes started`() {
        val r = record("CN", "2026-04-01T11:00:00Z", "2026-04-01T09:00:00Z")
        sessionOf(r) shouldBe SessionParse.Unattributable(UnattributableReason.ENDED_BEFORE_STARTED)
    }

    @Test
    fun `sessionOf is Valid with a zero-length session when ended equals started`() {
        val r = record("CN", "2026-04-01T09:00:00Z", "2026-04-01T09:00:00Z")
        val parse = sessionOf(r) as SessionParse.Valid
        parse.session.started shouldBe parse.session.ended
    }

    // --- Feature example 1's arithmetic at ledger level ---

    @Test
    fun `example 1 - CN 6h and GF 4h read back independently over a covering window`() {
        val ledger = SessionLedger()
        val cn = record("CN", "2026-04-01T00:00:00Z", "2026-04-01T06:00:00Z")
        val gf = record("GF", "2026-04-01T00:00:00Z", "2026-04-01T04:00:00Z")
        ledger.apply(addDelta(cn, 1))
        ledger.apply(addDelta(gf, 2))

        val from = Instant.parse("2026-04-01T00:00:00Z")
        val to = Instant.parse("2026-04-01T06:00:00Z")
        ledger.hoursBetween("CN", from, to) shouldBe (6.0 plusOrMinus TOLERANCE)
        ledger.hoursBetween("GF", from, to) shouldBe (4.0 plusOrMinus TOLERANCE)
        ledger.projects() shouldBe setOf("CN", "GF")
    }

    // --- fpml.3-D2: overlap attribution at every boundary ---

    @Test
    fun `a session straddling a boundary is split by overlap and the two halves sum to the whole`() {
        val ledger = SessionLedger()
        val session = record("CN", "2026-04-01T09:00:00Z", "2026-04-01T11:00:00Z") // 2h
        ledger.apply(addDelta(session, 1))

        val queryOverlappingTail =
            ledger.hoursBetween("CN", Instant.parse("2026-04-01T10:00:00Z"), Instant.parse("2026-04-01T12:00:00Z"))
        queryOverlappingTail shouldBe (1.0 plusOrMinus TOLERANCE)

        val firstHalf =
            ledger.hoursBetween("CN", Instant.parse("2026-04-01T08:00:00Z"), Instant.parse("2026-04-01T10:00:00Z"))
        val secondHalf =
            ledger.hoursBetween("CN", Instant.parse("2026-04-01T10:00:00Z"), Instant.parse("2026-04-01T12:00:00Z"))
        (firstHalf + secondHalf) shouldBe (2.0 plusOrMinus TOLERANCE)
    }

    // --- Membership flip semantics ---

    @Test
    fun `a second add-tag for an already-live record does not double count`() {
        val ledger = SessionLedger()
        val r = record("CN", "2026-04-01T00:00:00Z", "2026-04-01T02:00:00Z") // 2h
        ledger.apply(addDelta(r, 1))
        ledger.apply(addDelta(r, 2)) // same record, a second (redundant) add-tag

        val hours = ledger.hoursBetween("CN", Instant.parse("2026-04-01T00:00:00Z"), Instant.parse("2026-04-01T02:00:00Z"))
        hours shouldBe (2.0 plusOrMinus TOLERANCE) // not 4.0
    }

    @Test
    fun `a del carrying the observed tags removes the session`() {
        val ledger = SessionLedger()
        val r = record("CN", "2026-04-01T00:00:00Z", "2026-04-01T02:00:00Z")
        ledger.apply(addDelta(r, 1))
        ledger.apply(delDelta(r, 1))

        ledger.hoursBetween("CN", Instant.parse("2026-04-01T00:00:00Z"), Instant.parse("2026-04-01T02:00:00Z")) shouldBe 0.0
        ledger.projects().shouldBeEmpty()
    }

    @Test
    fun `a del with unobserved tags is a no-op`() {
        val ledger = SessionLedger()
        val r = record("CN", "2026-04-01T00:00:00Z", "2026-04-01T02:00:00Z")
        ledger.apply(addDelta(r, 1))
        ledger.apply(delDelta(r, 99)) // tag this ledger never added

        val hours = ledger.hoursBetween("CN", Instant.parse("2026-04-01T00:00:00Z"), Instant.parse("2026-04-01T02:00:00Z"))
        hours shouldBe (2.0 plusOrMinus TOLERANCE) // still live
        ledger.projects() shouldBe setOf("CN")
    }

    @Test
    fun `after remove-then-re-add with a fresh tag the session counts once`() {
        val ledger = SessionLedger()
        val r = record("CN", "2026-04-01T00:00:00Z", "2026-04-01T02:00:00Z")
        ledger.apply(addDelta(r, 1))
        ledger.apply(delDelta(r, 1))
        ledger.apply(addDelta(r, 2)) // fresh tag, not tag 1

        val hours = ledger.hoursBetween("CN", Instant.parse("2026-04-01T00:00:00Z"), Instant.parse("2026-04-01T02:00:00Z"))
        hours shouldBe (2.0 plusOrMinus TOLERANCE) // once, not zero and not double
    }

    // --- Unattributable accounting ---

    @Test
    fun `an unparseable record appears in unattributable and unattributableByReason, contributes 0h, and both clear on removal`() {
        val ledger = SessionLedger()
        val bad = record("CN", "garbled", "2026-04-01T02:00:00Z")
        ledger.apply(addDelta(bad, 1))

        ledger.unattributable shouldBe setOf(bad)
        ledger.unattributableByReason() shouldBe mapOf(UnattributableReason.UNPARSEABLE_STARTED to 1L)
        ledger.hoursBetween("CN", Instant.parse("2026-04-01T00:00:00Z"), Instant.parse("2026-04-01T03:00:00Z")) shouldBe 0.0
        ledger.projects().shouldBeEmpty() // an unattributable record never has a live session

        ledger.apply(delDelta(bad, 1))
        ledger.unattributable.shouldBeEmpty()
        ledger.unattributableByReason() shouldBe emptyMap()
    }

    // --- Incrementality, evidenced by count ---

    @Test
    fun `hoursBetween visits only sessions ending after from, and apply never touches sessionsVisited`() {
        val ledger = SessionLedger()
        val from = Instant.parse("2026-06-01T00:00:00Z")
        val to = Instant.parse("2026-06-02T00:00:00Z")

        // 1000 sessions ending well BEFORE `from` — a full scan would visit these too.
        repeat(1000) { i ->
            val ended = from.minusSeconds((i + 1) * 60L)
            val started = ended.minusSeconds(3600)
            val r = record("P", started.toString(), ended.toString(), workItem = "before-$i")
            ledger.apply(addDelta(r, i.toLong()))
        }

        // 5 sessions ending AFTER `from`, inside the query window.
        repeat(5) { i ->
            val started = from.plusSeconds(i * 60L)
            val ended = started.plusSeconds(30L)
            val r = record("P", started.toString(), ended.toString(), workItem = "after-$i")
            ledger.apply(addDelta(r, 1000L + i))
        }

        val hours = ledger.hoursBetween("P", from, to)
        (hours > 0.0) shouldBe true
        (ledger.sessionsVisited <= 5L) shouldBe true

        val visitedAfterRead = ledger.sessionsVisited
        val extra = record("P", to.toString(), to.plusSeconds(60).toString(), workItem = "extra")
        ledger.apply(addDelta(extra, 9999L))
        ledger.sessionsVisited shouldBe visitedAfterRead // apply is index maintenance, not a read
    }
}
