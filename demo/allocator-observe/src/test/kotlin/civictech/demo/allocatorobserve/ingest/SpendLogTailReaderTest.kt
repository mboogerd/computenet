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
    private fun poll(store: SpendOffsetStore = OffsetCheckpoint(runDir)): TailBatch {
        var seen: TailBatch? = null
        val returned = SpendLogTailReader(log, store).poll { seen = it }
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
