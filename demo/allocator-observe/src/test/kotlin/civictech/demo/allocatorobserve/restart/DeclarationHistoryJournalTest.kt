package civictech.demo.allocatorobserve.restart

import civictech.cell.data.SetCell
import civictech.demo.allocatorobserve.SpendRecord
import civictech.demo.allocatorobserve.declaration.AllocationDeclaration
import civictech.demo.allocatorobserve.declaration.DeclarationEvent
import civictech.demo.allocatorobserve.http.IngestFailureCounts
import civictech.demo.allocatorobserve.http.IngestHealth
import civictech.demo.allocatorobserve.http.ServedState
import civictech.demo.allocatorobserve.http.toJson
import civictech.demo.allocatorobserve.view.AllocatorReportViews
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant

/**
 * [DeclarationHistoryJournal] on its own (task `computenet-fpml.5.2`, design
 * fpml.5-D4b): append, replay, the accounting for a line that cannot be parsed,
 * and the shape agreement with the served exchange document (fpml.5-D7).
 *
 * The shape test is the one that matters beyond this file. The journal writes
 * its own DTOs rather than importing `http/AllocatorJson.kt`'s, so nothing but
 * a test stops the two from drifting apart — and a drifted journal is not a
 * compile error, it is a restart that silently reconstructs a different
 * declaration. So the agreement is checked against a REAL served document built
 * from the real cells, not against a hand-written string.
 */
class DeclarationHistoryJournalTest {

    @TempDir
    lateinit var tmp: Path

    private val runDir: Path get() = tmp.resolve("run")

    private companion object {
        const val CN = "computenet"
        const val GF = "glass-factory"

        val NOW: Instant = Instant.parse("2026-08-15T12:00:00Z")
        val WINDOW: Duration = Duration.ofHours(24)
        val EARLY: Instant = Instant.parse("2026-08-01T00:00:00Z")
    }

    private fun declaration(computenet: Double, glassFactory: Double, window: String? = null) =
        AllocationDeclaration(
            weights = mapOf(CN to computenet, GF to glassFactory),
            monthlyCapHours = 100.0,
            window = window,
        )

    private val d1 = DeclarationEvent(Instant.parse("2026-08-08T00:00:00Z"), declaration(60.0, 40.0))
    private val d2 = DeclarationEvent(Instant.parse("2026-08-12T00:00:00Z"), declaration(30.0, 70.0))

    private fun journalFile(): Path = runDir.resolve("declaration-history")

    @Test
    fun `two appended events replay into a fresh cell`() {
        val journal = DeclarationHistoryJournal(runDir)
        journal.append(d1)
        journal.append(d2)

        val cell = SetCell<DeclarationEvent>()
        DeclarationHistoryJournal(runDir).replayInto(cell) shouldBe 2
        cell.membership() shouldBe setOf(d1, d2)
    }

    @Test
    fun `replaying twice leaves the same membership`() {
        val journal = DeclarationHistoryJournal(runDir)
        journal.append(d1)
        journal.append(d2)

        val cell = SetCell<DeclarationEvent>()
        val replay = DeclarationHistoryJournal(runDir)
        replay.replayInto(cell)
        replay.replayInto(cell)

        // The count is lines replayed, not membership: the cell is a set and a
        // replayed event equals the one that produced its line.
        cell.membership() shouldBe setOf(d1, d2)
        replay.replayFailures shouldBe 0L
    }

    @Test
    fun `an unparseable line is counted and the good lines around it still replay`() {
        val journal = DeclarationHistoryJournal(runDir)
        journal.append(d1)
        Files.writeString(journalFile(), "{not json at all\n", StandardOpenOption.APPEND)
        journal.append(d2)

        val cell = SetCell<DeclarationEvent>()
        val replay = DeclarationHistoryJournal(runDir)
        replay.replayInto(cell) shouldBe 2
        replay.replayFailures shouldBe 1L
        cell.membership() shouldBe setOf(d1, d2)
    }

    @Test
    fun `a torn trailing append is ignored rather than counted as a failure`() {
        val journal = DeclarationHistoryJournal(runDir)
        journal.append(d1)
        // No terminating newline: a half-written append, not a broken record.
        Files.writeString(journalFile(), """{"observedAt":"2026-08-12T00""", StandardOpenOption.APPEND)

        val cell = SetCell<DeclarationEvent>()
        val replay = DeclarationHistoryJournal(runDir)
        replay.replayInto(cell) shouldBe 1
        replay.replayFailures shouldBe 0L
        cell.membership() shouldBe setOf(d1)
    }

    @Test
    fun `an absent journal replays nothing`() {
        val cell = SetCell<DeclarationEvent>()
        val journal = DeclarationHistoryJournal(runDir)
        Files.exists(journalFile()) shouldBe false
        journal.replayInto(cell) shouldBe 0
        journal.replayFailures shouldBe 0L
        cell.membership().isEmpty() shouldBe true
    }

    @Test
    fun `a journal line's declaration object equals the served document's`() {
        val event = DeclarationEvent(EARLY, declaration(60.0, 40.0, window = "168h"))

        val journal = DeclarationHistoryJournal(runDir)
        journal.append(event)
        val line = Files.readAllLines(journalFile()).single()
        val journalled = Json.parseToJsonElement(line).jsonObject.getValue("declaration")

        val served = Json.parseToJsonElement(servedState(event).toJson())
            .jsonObject.getValue("report").jsonObject
            .getValue("window").jsonObject
            .getValue("subIntervals").jsonArray[0].jsonObject
            .getValue("declaration")

        journalled shouldBe served
    }

    /** A real [ServedState] over the real cells, built as `AllocatorJsonTest` builds one. */
    private fun servedState(declEvent: DeclarationEvent): ServedState {
        val records = SetCell<SpendRecord>()
        val declarations = SetCell<DeclarationEvent>()
        val views = AllocatorReportViews.derivedFrom(records, declarations, WINDOW, now = { NOW })

        declarations.inlet.call.add(declEvent)
        records.inlet.call.add(
            SpendRecord(
                v = 1,
                project = CN,
                machine = "m1",
                workItem = "w1",
                started = "2026-08-14T13:00:00Z",
                ended = "2026-08-14T19:00:00Z",
            ),
        )

        val report = views.publish()
        return ServedState(
            report = report,
            ingest = IngestHealth(
                recordCount = 1,
                checkpointOffset = null,
                reBaselineCount = 0L,
                polls = 1L,
                lastPollAt = NOW,
                failures = IngestFailureCounts(malformed = 0L, unknownVersion = 0L, declarationParseFailed = 0L),
                declarationEvents = 1,
            ),
            records = records.membership(),
            declarations = listOf(declEvent),
        )
    }
}
