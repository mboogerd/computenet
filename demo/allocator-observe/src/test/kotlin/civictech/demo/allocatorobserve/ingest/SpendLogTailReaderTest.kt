package civictech.demo.allocatorobserve.ingest

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** File-mechanics tests for the cursor-returning, persistence-free tail reader. */
class SpendLogTailReaderTest {

    @TempDir
    lateinit var dir: Path

    private val log: Path get() = dir.resolve("spend.jsonl")
    private var committed: CheckpointState? = null

    private fun append(text: String) {
        Files.writeString(log, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun poll(
        chunkSize: Int = SpendLogTailReader.DEFAULT_CHUNK_SIZE,
        maxLinesPerBatch: Int = SpendLogTailReader.DEFAULT_MAX_LINES_PER_BATCH,
        commit: Boolean = true,
    ): TailBatch {
        val batches = mutableListOf<TailBatch>()
        val summary = SpendLogTailReader(
            logPath = log,
            committed = { committed },
            maxLinesPerBatch = maxLinesPerBatch,
            chunkSize = chunkSize,
        ).poll(batches::add)
        batches.isEmpty() shouldBe false
        batches.dropLast(1).forEach { it.last shouldBe false }
        batches.last().last shouldBe true
        val lines = batches.flatMap(TailBatch::lines)
        summary.lineCount shouldBe lines.size.toLong()
        summary.handOffs shouldBe batches.size
        summary.offset shouldBe batches.last().offset
        summary.next.offset shouldBe summary.offset
        if (commit) committed = summary.next
        return TailBatch(summary.reason, lines, summary.offset, last = true)
    }

    private fun handOffs(chunkSize: Int, maxLinesPerBatch: Int): List<TailBatch> {
        val batches = mutableListOf<TailBatch>()
        val summary = SpendLogTailReader(log, { committed }, maxLinesPerBatch, chunkSize).poll(batches::add)
        committed = summary.next
        return batches
    }

    @Test
    fun `repeated polls read each byte once while the log grows`() {
        append("one\ntwo\n")
        poll().also {
            it.reason shouldBe TailReason.FirstStart
            it.lines shouldContainExactly listOf("one", "two")
            it.offset shouldBe 8L
        }

        poll().also {
            it.reason shouldBe TailReason.Resumed(8L)
            it.lines.shouldBeEmpty()
        }

        append("three\n")
        poll().also {
            it.reason shouldBe TailReason.Resumed(8L)
            it.lines shouldContainExactly listOf("three")
            it.offset shouldBe 14L
        }
    }

    @Test
    fun `a new reader resumes from the caller committed cursor`() {
        append("one\ntwo\n")
        poll().offset shouldBe 8L
        append("three\nfour\n")

        poll().also {
            it.reason shouldBe TailReason.Resumed(8L)
            it.lines shouldContainExactly listOf("three", "four")
            it.offset shouldBe 19L
        }
    }

    @Test
    fun `truncation and replacement re-baseline from zero`() {
        append("aaa\nbbb\n")
        poll()

        Files.writeString(log, "aaa\n")
        val truncated = poll()
        truncated.reason.shouldBeInstanceOf<TailReason.ReBaselined>().cause shouldBe
            ReBaselineCause.Truncated(8L, 4L)
        truncated.lines shouldContainExactly listOf("aaa")

        Files.writeString(log, "xxx\nyyy\nzzz\n")
        val replaced = poll()
        replaced.reason.shouldBeInstanceOf<TailReason.ReBaselined>()
            .cause.shouldBeInstanceOf<ReBaselineCause.Replaced>()
        replaced.lines shouldContainExactly listOf("xxx", "yyy", "zzz")
    }

    @Test
    fun `a trailing partial line is withheld until its newline arrives`() {
        append("full\npart")
        poll().also {
            it.lines shouldContainExactly listOf("full")
            it.offset shouldBe 5L
        }
        append("ial\n")
        poll().also {
            it.reason shouldBe TailReason.Resumed(5L)
            it.lines shouldContainExactly listOf("partial")
        }
        poll().lines.shouldBeEmpty()
    }

    @Test
    fun `the cursor is exposed only after the final hand-off returns`() {
        append((0 until 100).joinToString("") { "line-%04d\n".format(it) })
        val before = committed
        var handOffs = 0
        val summary = SpendLogTailReader(log, { committed }, maxLinesPerBatch = 32, chunkSize = 64).poll {
            committed shouldBe before
            handOffs++
        }
        handOffs shouldBe 4
        committed shouldBe before
        summary.next.offset shouldBe 1000L
        committed = summary.next
        committed?.offset shouldBe 1000L
    }

    @Test
    fun `a consumer failure leaves the caller cursor untouched`() {
        append("one\ntwo\n")
        assertThrows<IllegalStateException> {
            SpendLogTailReader(log, { committed }).poll { error("consumer failed") }
        }
        committed shouldBe null
        poll().reason shouldBe TailReason.FirstStart
    }

    @Test
    fun `an absent log returns a zero cursor and arrival resumes from zero`() {
        val absent = poll()
        absent.reason shouldBe TailReason.LogAbsent
        absent.lines.shouldBeEmpty()
        committed?.offset shouldBe 0L

        append("one\n")
        poll().also {
            it.reason shouldBe TailReason.Resumed(0L)
            it.lines shouldContainExactly listOf("one")
        }
    }

    @Test
    fun `chunk boundaries and multi-byte characters preserve complete lines`() {
        append("abcdefgh\na€b\n" + "x".repeat(200) + "\n")
        poll(chunkSize = 2).lines shouldContainExactly
            listOf("abcdefgh", "a€b", "x".repeat(200))
    }

    @Test
    fun `large reads are handed off in bounded batches`() {
        val lines = (0 until 300).map { "line-%04d".format(it) }
        append(lines.joinToString("") { "$it\n" })

        val batches = handOffs(chunkSize = 64, maxLinesPerBatch = 32)
        batches.map { it.lines.size }.forEach { (it <= 32) shouldBe true }
        batches.size shouldBe 10
        batches.flatMap(TailBatch::lines) shouldContainExactly lines
        batches.last().last shouldBe true
    }

    @Test
    fun `a later hand-off failure still returns no cursor`() {
        append((0 until 100).joinToString("") { "line-%04d\n".format(it) })
        var seen = 0
        assertThrows<IllegalStateException> {
            SpendLogTailReader(log, { committed }, maxLinesPerBatch = 32, chunkSize = 64).poll {
                if (++seen == 3) error("third hand-off")
            }
        }
        committed shouldBe null
    }

    @Test
    fun `non-positive bounds are refused`() {
        assertThrows<IllegalArgumentException> { SpendLogTailReader(log, { null }, maxLinesPerBatch = 0) }
        assertThrows<IllegalArgumentException> { SpendLogTailReader(log, { null }, chunkSize = 0) }
    }
}
