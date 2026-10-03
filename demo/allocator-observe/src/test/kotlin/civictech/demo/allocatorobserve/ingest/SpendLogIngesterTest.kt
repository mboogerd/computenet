package civictech.demo.allocatorobserve.ingest

import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.durability.InMemoryJournal
import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.JournalRecords
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.demo.allocatorobserve.SpendRecord
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** End-to-end spend ingestion through a hosted, kernel-durable fold. */
class SpendLogIngesterTest {

    @TempDir
    lateinit var dir: Path

    private val log: Path get() = dir.resolve("spend.jsonl")

    private fun line(workItem: String, ended: String = "2026-08-23T09:30:00Z"): String =
        """{"v":1,"project":"socaity","machine":"MacBoo","work_item":"$workItem",""" +
            """"started":"2026-08-23T09:00:00Z","ended":"$ended"}"""

    private fun record(workItem: String, ended: String = "2026-08-23T09:30:00Z") = SpendRecord(
        1, "socaity", "MacBoo", workItem, "2026-08-23T09:00:00Z", ended,
    )

    private fun append(vararg lines: String) {
        Files.writeString(
            log,
            lines.joinToString("") { "$it\n" },
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private inner class Rig(
        val journal: InMemoryJournal = InMemoryJournal(),
        maxLinesPerBatch: Int = SpendLogTailReader.DEFAULT_MAX_LINES_PER_BATCH,
    ) {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val cell = SetCell<SpendRecord>()
        val input: civictech.cell.host.DurableInput
        val ingester: SpendLogIngester

        init {
            host.managementInlet.call.spawn(cell)
            input = host.durableInput(cell.ref, "spend")
            val ops = checkNotNull(host.lookup(TypedRef<SetApi<SpendRecord>>(cell.ref))).inlet.call
            ingester = SpendLogIngester(log, ops, input, cell::membership, maxLinesPerBatch)
        }

        fun poll(): SpendPollOutcome = ingester.poll().also { controller.runToIdle() }
    }

    @Test
    fun `three valid lines become three records with no failures`() {
        append(line("a"), line("b"), line("c"))
        val rig = Rig()

        val outcome = rig.poll()

        outcome.added shouldBe 3
        outcome.removed shouldBe 0
        rig.ingester.view() shouldBe setOf(record("a"), record("b"), record("c"))
        rig.ingester.failures shouldBe SpendIngestFailures()
    }

    @Test
    fun `a second ingester resumes from the durable input cursor`() {
        append(line("a"), "not json", line("b"), line("c"))
        val rig = Rig()
        rig.poll()
        val offset = Files.size(log)

        append(line("d"), line("e"))
        val resumed = SpendLogIngester(
            log,
            checkNotNull(rig.host.lookup(TypedRef<SetApi<SpendRecord>>(rig.cell.ref))).inlet.call,
            rig.input,
            rig.cell::membership,
        )
        val outcome = resumed.poll()
        rig.controller.runToIdle()

        outcome.reason shouldBe TailReason.Resumed(offset)
        outcome.added shouldBe 2
        resumed.failures shouldBe SpendIngestFailures()
        resumed.view() shouldBe setOf(record("a"), record("b"), record("c"), record("d"), record("e"))
    }

    @Test
    fun `truncation re-baselines the fold idempotently`() {
        append(line("a"), line("b"), line("c"))
        val rig = Rig()
        rig.poll()
        Files.writeString(log, line("a") + "\n")

        rig.poll().also {
            it.reason.shouldBeInstanceOf<TailReason.ReBaselined>()
            it.added shouldBe 0
            it.removed shouldBe 2
        }
        rig.ingester.view() shouldBe setOf(record("a"))

        rig.input.commit { CheckpointState(Files.size(log) + 4096, "0".repeat(64)) }
        rig.poll().also {
            it.reason.shouldBeInstanceOf<TailReason.ReBaselined>()
            it.added shouldBe 0
            it.removed shouldBe 0
        }
    }

    @Test
    fun `bad lines are accounted without stopping valid ingestion`() {
        append(
            line("a"),
            "not json",
            line("b"),
            """{"v":99,"project":"socaity","machine":"MacBoo"}""",
            line("c"),
        )
        val rig = Rig()

        val outcome = rig.poll()

        outcome.failures shouldBe SpendIngestFailures(malformed = 1, unknownVersion = 1)
        outcome.added shouldBe 3
        rig.ingester.view() shouldBe setOf(record("a"), record("b"), record("c"))
    }

    @Test
    fun `record identity is the full tuple`() {
        append(line("dup"), line("dup"), line("twin"), line("twin", "2026-08-23T10:30:00Z"))
        val rig = Rig()
        rig.poll()

        rig.ingester.view() shouldBe
            setOf(record("dup"), record("twin"), record("twin", "2026-08-23T10:30:00Z"))
    }

    @Test
    fun `cursor and fold mutations share one durable input record`() {
        append(line("a"))
        val rig = Rig()

        rig.poll()

        (rig.input.committed() as CheckpointState).offset shouldBe Files.size(log)
        rig.ingester.view() shouldBe setOf(record("a"))
        val inputs = rig.journal.replay().map(JournalRecords::decode).filterIsInstance<DecodedJournalRecord.Input>()
        inputs.size shouldBe 1
        inputs.single().name shouldBe "spend"
        inputs.single().frames.size shouldBe 1
    }

    @Test
    fun `multi-hand-off re-baseline converges on the whole replacement`() {
        append(*(0 until 40).map { line("old-$it") }.toTypedArray())
        val rig = Rig(maxLinesPerBatch = 7)
        rig.poll()
        val replacement = (0 until 33).map { line("new-$it") }
        Files.writeString(log, replacement.joinToString("") { "$it\n" })

        val outcome = rig.poll()

        outcome.reason.shouldBeInstanceOf<TailReason.ReBaselined>()
        outcome.added shouldBe 33
        outcome.removed shouldBe 40
        rig.ingester.view() shouldBe (0 until 33).map { record("new-$it") }.toSet()
    }

    @Test
    fun `an absent log leaves the fold untouched`() {
        append(line("a"))
        val rig = Rig()
        rig.poll()
        Files.delete(log)

        rig.poll().reason shouldBe TailReason.LogAbsent
        rig.ingester.view() shouldBe setOf(record("a"))
    }
}
