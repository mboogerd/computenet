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

/**
 * File-mechanics tests for `computenet-fpml.1.2`: the byte-offset checkpoint
 * and the truncation/replacement detection that makes the feature's rules 1
 * and 3 true at the byte level.
 *
 * Everything runs in a JUnit temp dir — no real socaity spend log exists yet
 * and no path is hardcoded anywhere (design note fpml.1-D1).
 */
class SpendLogTailReaderTest {

    @TempDir
    lateinit var dir: Path

    private val log: Path get() = dir.resolve("spend.jsonl")
    private val runDir: Path get() = dir.resolve("run")

    private fun append(text: String) {
        Files.writeString(log, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    /** Polls a fresh reader over the same run dir, collecting what the consumer saw. */
    private fun poll(
        store: SpendOffsetStore = OffsetCheckpoint(runDir),
        chunkSize: Int = SpendLogTailReader.DEFAULT_CHUNK_SIZE,
    ): TailBatch {
        var seen: TailBatch? = null
        val returned = SpendLogTailReader(log, store, chunkSize).poll { seen = it }
        // The value handed to the consumer and the value returned are the same
        // batch — a caller must not have to choose between them.
        seen shouldBe returned
        return returned
    }

    @Test
    fun `repeated polls read each byte at most once while the log only grows`() {
        append("one\ntwo\n")
        val first = poll()
        first.reason shouldBe TailReason.FirstStart
        first.lines shouldContainExactly listOf("one", "two")
        first.offset shouldBe 8L

        // Nothing appended: nothing re-read.
        val idle = poll()
        idle.reason shouldBe TailReason.Resumed(8L)
        idle.lines.shouldBeEmpty()
        idle.offset shouldBe 8L

        append("three\n")
        val third = poll()
        third.reason shouldBe TailReason.Resumed(8L)
        third.lines shouldContainExactly listOf("three")
        third.offset shouldBe 14L
    }

    @Test
    fun `a restart resumes at the persisted offset and reads only the new bytes`() {
        append("one\ntwo\n")
        poll().offset shouldBe 8L

        // A brand-new reader AND a brand-new checkpoint over the same run
        // directory — i.e. a restarted process, with nothing carried in memory.
        append("three\nfour\n")
        val resumed = poll(OffsetCheckpoint(runDir))
        resumed.reason shouldBe TailReason.Resumed(8L)
        resumed.lines shouldContainExactly listOf("three", "four")
        resumed.offset shouldBe 19L
    }

    @Test
    fun `truncating the log below the checkpoint offset re-baselines from zero`() {
        append("aaa\nbbb\n")
        poll().offset shouldBe 8L

        Files.writeString(log, "aaa\n")
        val rebaselined = poll()

        val reason = rebaselined.reason.shouldBeInstanceOf<TailReason.ReBaselined>()
        reason.cause shouldBe ReBaselineCause.Truncated(checkpointOffset = 8L, currentLength = 4L)
        rebaselined.lines shouldContainExactly listOf("aaa")
        rebaselined.offset shouldBe 4L

        // And the re-baseline is not sticky: the next poll resumes normally
        // from the re-baselined position.
        append("ccc\n")
        val after = poll()
        after.reason shouldBe TailReason.Resumed(4L)
        after.lines shouldContainExactly listOf("ccc")
    }

    @Test
    fun `replacing the log with different content of at least the checkpoint length re-baselines`() {
        append("aaa\nbbb\n")
        poll().offset shouldBe 8L

        // 12 bytes >= the 8-byte checkpoint, so length alone says "it grew".
        // Only the head fingerprint can tell that it is a different log.
        Files.writeString(log, "xxx\nyyy\nzzz\n")
        val rebaselined = poll()

        val reason = rebaselined.reason.shouldBeInstanceOf<TailReason.ReBaselined>()
        val cause = reason.cause.shouldBeInstanceOf<ReBaselineCause.Replaced>()
        cause.checkpointOffset shouldBe 8L
        (cause.expectedFingerprint == cause.actualFingerprint) shouldBe false
        rebaselined.lines shouldContainExactly listOf("xxx", "yyy", "zzz")
        rebaselined.offset shouldBe 12L
    }

    @Test
    fun `a trailing partial line is withheld until its newline arrives, then delivered once`() {
        append("full\npart")
        val partial = poll()
        partial.lines shouldContainExactly listOf("full")
        // Offset stops after "full\n" — it does NOT advance over "part".
        partial.offset shouldBe 5L

        append("ial\n")
        val completed = poll()
        completed.reason shouldBe TailReason.Resumed(5L)
        completed.lines shouldContainExactly listOf("partial")
        completed.offset shouldBe 13L

        // Exactly once: a further poll delivers nothing.
        poll().lines.shouldBeEmpty()
    }

    @Test
    fun `the checkpoint is written only after the batch is handed to the consumer`() {
        append("one\ntwo\n")

        val events = mutableListOf<String>()
        val store = RecordingStore(OffsetCheckpoint(runDir), events)
        SpendLogTailReader(log, store).poll { batch -> events += "consumed(${batch.lines.size})" }

        events shouldContainExactly listOf("read", "consumed(2)", "write(8)")
    }

    @Test
    fun `a consumer that throws leaves the checkpoint untouched, so the batch is redelivered`() {
        append("one\ntwo\n")

        val checkpoint = OffsetCheckpoint(runDir)
        assertThrows<IllegalStateException> {
            SpendLogTailReader(log, checkpoint).poll { error("consumer failed") }
        }
        checkpoint.read() shouldBe null

        val retried = poll(checkpoint)
        retried.reason shouldBe TailReason.FirstStart
        retried.lines shouldContainExactly listOf("one", "two")
    }

    @Test
    fun `a nonexistent log yields an empty batch rather than throwing`() {
        val absent = poll()
        absent.reason shouldBe TailReason.LogAbsent
        absent.lines.shouldBeEmpty()
        absent.offset shouldBe 0L

        // It is also not a poisoned state: once the log arrives it is read
        // normally, from the beginning.
        append("one\n")
        val arrived = poll()
        arrived.reason shouldBe TailReason.FirstStart
        arrived.lines shouldContainExactly listOf("one")
    }

    @Test
    fun `an empty log is neither an error nor a re-baseline trigger`() {
        Files.createFile(log)
        poll().lines.shouldBeEmpty()

        append("one\n")
        val grown = poll()
        // Offset 0 was checkpointed, so this is a resume, not a re-baseline:
        // an empty head window must not read as a changed identity.
        grown.reason shouldBe TailReason.Resumed(0L)
        grown.lines shouldContainExactly listOf("one")
    }

    // ---- computenet-v5c7: the read is chunked, and the chunk size is a parameter ----
    //
    // The defect these cover is a whole-file read (TailReason.FirstStart or
    // TailReason.ReBaselined) over a range that does not fit an Int. A 4 GiB
    // fixture is not a unit test, so the CHUNK SIZE is the parameter that makes
    // the multi-chunk arithmetic — the boundary carry, the offset accounting —
    // reachable at a size a temp dir can hold. What these tests exercise is the
    // same loop production runs; only the number of iterations differs.

    /** `count` lines of the form `line-0000`, plus their newlines. Returns the lines. */
    private fun manyLines(count: Int): List<String> {
        val lines = (0 until count).map { "line-%04d".format(it) }
        append(lines.joinToString("") { it + "\n" })
        return lines
    }

    @Test
    fun `a line whose bytes straddle a chunk boundary is delivered exactly once and intact`() {
        // 7-byte chunks against 9-byte lines ("abcdefgh\n"): no line can begin
        // and end inside one chunk, so EVERY line straddles a boundary.
        append("abcdefgh\nijklmnop\nqrstuvwx\n")
        val batch = poll(chunkSize = 7)
        batch.reason shouldBe TailReason.FirstStart
        batch.lines shouldContainExactly listOf("abcdefgh", "ijklmnop", "qrstuvwx")
        batch.offset shouldBe 27L

        // Exactly once: the straddling line is not re-emitted by a later poll.
        poll(chunkSize = 7).lines.shouldBeEmpty()
    }

    @Test
    fun `a chunk boundary landing exactly on a newline neither duplicates nor drops a line`() {
        // 4-byte chunks against 4-byte lines ("abc\n"): every chunk ends exactly
        // on a newline, so the carry is empty at every boundary.
        append("abc\ndef\nghi\n")
        val batch = poll(chunkSize = 4)
        batch.lines shouldContainExactly listOf("abc", "def", "ghi")
        batch.offset shouldBe 12L
    }

    @Test
    fun `a line longer than one chunk is assembled across chunks and delivered intact`() {
        // The carry, not the read buffer, is what has to grow here: a 200-byte
        // line read through an 8-byte buffer spans 25 chunks.
        val long = "x".repeat(200)
        append("short\n" + long + "\nafter\n")
        val batch = poll(chunkSize = 8)
        batch.lines shouldContainExactly listOf("short", long, "after")
        batch.offset shouldBe (6 + 201 + 6).toLong()
    }

    @Test
    fun `the offset advances across a multi-chunk read without re-reading or skipping a byte`() {
        // 500 lines of 10 bytes = 5000 bytes read through a 64-byte buffer:
        // ~79 chunks in one poll, then a second poll over an append that itself
        // spans several chunks. The union of the two batches must be the file
        // exactly once, in order, and the offsets must chain.
        val first = manyLines(500)
        val batchA = poll(chunkSize = 64)
        batchA.reason shouldBe TailReason.FirstStart
        batchA.lines shouldContainExactly first
        batchA.offset shouldBe 5000L

        val second = (500 until 700).map { "line-%04d".format(it) }
        append(second.joinToString("") { it + "\n" })
        val batchB = poll(chunkSize = 64)
        batchB.reason shouldBe TailReason.Resumed(5000L)
        batchB.lines shouldContainExactly second
        batchB.offset shouldBe 7000L

        // Nothing re-read, nothing skipped: concatenating what the two polls
        // delivered reconstructs the file byte-for-byte.
        (batchA.lines + batchB.lines).joinToString("") { it + "\n" } shouldBe
            Files.readString(log)

        // And the log is now idle, not mid-prefix.
        poll(chunkSize = 64).lines.shouldBeEmpty()
    }

    @Test
    fun `a whole-file multi-chunk read converges the materialized set on the current content`() {
        // Both whole-file reasons, since those are the only two that read a
        // range large enough for the narrowing to have mattered.
        val original = manyLines(300)
        poll(chunkSize = 37).lines shouldContainExactly original

        // Replace with LONGER, different content: a re-baseline re-reads the
        // whole file across many chunks and must deliver all of it.
        val replacement = (0 until 400).map { "fresh-%04d".format(it) }
        Files.writeString(log, replacement.joinToString("") { it + "\n" })
        val rebaselined = poll(chunkSize = 37)
        rebaselined.reason.shouldBeInstanceOf<TailReason.ReBaselined>()
        rebaselined.lines shouldContainExactly replacement
        rebaselined.offset shouldBe 4400L

        // The materialized set is exactly the file content, with nothing of the
        // superseded log left in it.
        rebaselined.lines.toSet() shouldBe replacement.toSet()
        rebaselined.lines.any { it in original } shouldBe false
    }

    @Test
    fun `a trailing partial line spanning the final chunks is withheld, then delivered once`() {
        val head = manyLines(50) // 500 bytes
        append("unterminated-tail-with-no-newline-yet")
        val partial = poll(chunkSize = 16)
        partial.lines shouldContainExactly head
        partial.offset shouldBe 500L

        append("-now-complete\n")
        val completed = poll(chunkSize = 16)
        completed.reason shouldBe TailReason.Resumed(500L)
        completed.lines shouldContainExactly listOf("unterminated-tail-with-no-newline-yet-now-complete")
        completed.offset shouldBe 551L
        poll(chunkSize = 16).lines.shouldBeEmpty()
    }

    @Test
    fun `a multi-byte character straddling a chunk boundary survives decoding`() {
        // "€" is 3 UTF-8 bytes; a 5-byte chunk splits it. Scanning for 0x0A
        // bytewise is only safe because a newline never occurs inside a
        // multi-byte sequence — this pins that.
        append("a€b\nc€d\n")
        poll(chunkSize = 5).lines shouldContainExactly listOf("a€b", "c€d")
    }

    @Test
    fun `a non-positive chunk size is refused at construction`() {
        assertThrows<IllegalArgumentException> { SpendLogTailReader(log, OffsetCheckpoint(runDir), 0) }
        assertThrows<IllegalArgumentException> { SpendLogTailReader(log, OffsetCheckpoint(runDir), -1) }
    }

    /** Records read/write calls into a shared ordering log, delegating the real work. */
    private class RecordingStore(
        private val delegate: SpendOffsetStore,
        private val events: MutableList<String>,
    ) : SpendOffsetStore {
        override fun read(): CheckpointState? = delegate.read().also { events += "read" }

        override fun write(state: CheckpointState) {
            events += "write(${state.offset})"
            delegate.write(state)
        }
    }
}
