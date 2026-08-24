package civictech.demo.allocatorobserve.ingest

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Persistence mechanics of the byte-offset checkpoint (`computenet-fpml.1.2`). */
class OffsetCheckpointTest {

    @TempDir
    lateinit var dir: Path

    private val runDir: Path get() = dir.resolve("run")

    @Test
    fun `an absent checkpoint reads as null, so the log is read from the beginning`() {
        OffsetCheckpoint(runDir).read() shouldBe null
    }

    @Test
    fun `a written state survives a new instance over the same run directory`() {
        val state = CheckpointState(offset = 42L, fingerprint = "deadbeef")
        OffsetCheckpoint(runDir).write(state)

        OffsetCheckpoint(runDir).read() shouldBe state
    }

    @Test
    fun `a write replaces the previous state and leaves no temp file behind`() {
        val checkpoint = OffsetCheckpoint(runDir)
        checkpoint.write(CheckpointState(1L, "aa"))
        checkpoint.write(CheckpointState(2L, "bb"))

        checkpoint.read() shouldBe CheckpointState(2L, "bb")
        Files.list(runDir).use { entries ->
            entries.map { it.fileName.toString() }.toList() shouldBe listOf("spend-offset")
        }
    }

    @Test
    fun `an unparseable checkpoint reads as null rather than as a wrong offset`() {
        val checkpoint = OffsetCheckpoint(runDir)
        checkpoint.write(CheckpointState(7L, "abc"))

        // Re-delivering bytes is idempotent downstream; skipping them is not,
        // so a garbled checkpoint must degrade to "start over", never to a
        // silently truncated read.
        for (garbage in listOf("", "   ", "notanumber abc", "7", "-1 abc", "7 ")) {
            Files.writeString(runDir.resolve("spend-offset"), garbage)
            checkpoint.read() shouldBe null
        }
    }

    @Test
    fun `the fingerprint window is bounded, so identical heads past it hash the same`() {
        val log = dir.resolve("spend.jsonl")
        Files.writeString(log, "x".repeat(FINGERPRINT_WINDOW_BYTES) + "tail")

        // Beyond the window the file's content stops mattering...
        val wide = fingerprintHead(log, Long.MAX_VALUE)
        wide shouldBe fingerprintHead(log, FINGERPRINT_WINDOW_BYTES.toLong())

        // ...but inside it, it very much does.
        wide shouldNotBe fingerprintHead(log, 16L)
    }

    @Test
    fun `fingerprinting an absent file or an empty window is total`() {
        val absent = dir.resolve("nope.jsonl")
        fingerprintHead(absent, 100L) shouldBe fingerprintHead(absent, 0L)
    }
}
