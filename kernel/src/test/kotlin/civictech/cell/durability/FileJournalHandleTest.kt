package civictech.cell.durability

import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [FileJournal] keeps ONE append handle instead of reopening the file per record
 * (computenet-sh8z). These are the properties that change could plausibly have
 * broken — the ones the format tests
 * ([JournalFormatVersionTest], [JournalCompatibilityTest]) do not reach because
 * they each use a journal instance once.
 *
 * The measurement that motivated the change, for whoever revisits it: on
 * macOS/arm64, `FileOutputStream(file, append = true)` costs ~3.3 ms against
 * ~0.03 ms for the `fsync` that follows, so BS-18's 73,146 appends spent 253 s
 * in `open`/`close` and 7.8 s in `fsync`. **fsync per append was kept**; only
 * the reopen was removed, so nothing here is a durability trade.
 */
class FileJournalHandleTest {

    private fun rec(s: String) = s.toByteArray()

    /**
     * The `kill -9` shape: a world appends and is then simply *abandoned* — no
     * close, no shutdown — and a second world opening the same path sees every
     * acknowledged record. This is the property a kept handle could have taken
     * away (buffered bytes stranded in the writer's address space), and the one
     * `DialogueRuntimeTest`'s BS-18 depends on across its three worlds.
     */
    @Test
    fun `records are visible to a second journal on the same path while the writer is still open`() {
        val dir = createTempDirectory("fj-handle").toFile()
        val file = dir.resolve("host.journal")

        val writer = FileJournal(file)
        writer.append(rec("alpha"))
        writer.append(rec("beta"))
        // writer is deliberately NOT closed and NOT dropped — it stays live below.

        FileJournal(file).replay().map { String(it) } shouldBe listOf("alpha", "beta")

        writer.append(rec("gamma"))
        FileJournal(file).replay().map { String(it) } shouldBe listOf("alpha", "beta", "gamma")
    }

    /**
     * The same, read by a genuinely separate OS process rather than another
     * object in this JVM — since "usable across processes" is the criterion, and
     * an in-JVM reader could in principle be satisfied by state a foreign
     * process cannot see. `wc -c` is enough: the byte count only reaches it if
     * the appends left this process on the way out.
     */
    @Test
    fun `an unclosed journal's bytes are already visible to a foreign process`() {
        val dir = createTempDirectory("fj-foreign").toFile()
        val file = dir.resolve("host.journal")

        val writer = FileJournal(file)
        repeat(4) { writer.append(rec("record-$it")) }

        val process = ProcessBuilder("/bin/sh", "-c", "wc -c < '${file.path}'")
            .redirectErrorStream(true)
            .start()
        val seenByOther = process.inputStream.bufferedReader().readText().trim().toLong()
        process.waitFor()

        seenByOther shouldBe file.length()
        assertTrue(seenByOther > 0, "a foreign process saw no bytes at all")
        // The writer is still open here; keep the reference alive to the end so
        // the assertion above cannot be satisfied by a cleaner having closed it.
        writer.append(rec("still-open"))
    }

    /**
     * [FileJournal.reset] renames a fresh file over the path. A handle held
     * across that rename would keep appending into the *unlinked* inode: the
     * compacted records would replay and the post-reset ones would silently
     * vanish. This is the one trap a kept handle introduces, so it is pinned
     * both for the resetting instance and for a fresh reader.
     */
    @Test
    fun `appends after reset land in the compacted file, not the replaced inode`() {
        val dir = createTempDirectory("fj-reset").toFile()
        val file = dir.resolve("host.journal")

        val journal = FileJournal(file)
        journal.append(rec("old-1"))
        journal.append(rec("old-2"))

        journal.reset(listOf(rec("checkpoint")))
        journal.append(rec("after-reset"))

        journal.replay().map { String(it) } shouldBe listOf("checkpoint", "after-reset")
        FileJournal(file).replay().map { String(it) } shouldBe listOf("checkpoint", "after-reset")
    }

    /**
     * Two threads appending through ONE journal — `TwoWriterDurabilityTest`'s
     * shape at the file level, and the only concurrency [FileJournal] supports
     * (its monitor is per instance). Each record is one `write` syscall against
     * an `O_APPEND` descriptor, so records interleave whole; a framing split
     * across two flushes would splice them and replay would decode garbage
     * lengths.
     *
     * Two *instances* on one path are a different case, unchanged by
     * computenet-sh8z and not a consequence of the kept handle — see
     * [FileJournal]'s KDoc ("Refusal, not interleaving or a shared instance")
     * and the `refuses` test below (computenet-k1by).
     */
    @Test
    fun `concurrent threads on one journal interleave whole records`() {
        val dir = createTempDirectory("fj-two-writers").toFile()
        val file = dir.resolve("host.journal")
        val journal = FileJournal(file)

        val perWriter = 200
        val writers = (0 until 2).map { w ->
            Thread {
                repeat(perWriter) { journal.append(rec("w$w-$it")) }
            }
        }
        writers.forEach { it.start() }
        writers.forEach { it.join(60_000) }

        val replayed = FileJournal(file).replay().map { String(it) }
        replayed.size shouldBe 2 * perWriter
        replayed.toSet().size shouldBe 2 * perWriter
        assertTrue(
            replayed.all { it.matches(Regex("""w[01]-\d+""")) },
            "a spliced record decoded as garbage: ${replayed.filterNot { it.matches(Regex("""w[01]-\d+""")) }}",
        )
    }

    /**
     * The header is written by whichever write first puts bytes in the file and
     * exactly once — a second journal appending to an already-headed file must
     * not emit a second [FileJournal.MAGIC], which would replay as a record of
     * length `0x434E4A4C`.
     */
    @Test
    fun `the version header is written once, by the first writer only`() {
        val dir = createTempDirectory("fj-header").toFile()
        val file = dir.resolve("host.journal")

        FileJournal(file).append(rec("first"))
        FileJournal(file).append(rec("second"))

        FileJournal(file).replay().map { String(it) } shouldBe listOf("first", "second")
        file.readBytes().take(FileJournal.MAGIC.size).toByteArray() shouldBe FileJournal.MAGIC
        // 8 header bytes, then each record's 4-byte length prefix and payload.
        file.length() shouldBe (8 + (4 + "first".length) + (4 + "second".length)).toLong()
    }

    /**
     * The actual computenet-k1by hazard: two LIVE `FileJournal` instances on
     * one path, appending concurrently from separate threads. Before the fix,
     * both instances' `needsHeader` checks raced (check-then-act across
     * instances, unguarded by either instance's own per-object
     * `@Synchronized` monitor — a monitor guards one instance, not two), so
     * both could observe an empty file and both would write a header. The
     * second `CNJL` landed mid-stream, where replay decoded it as a record of
     * length `0x434E4A4C` (~1.1 GB), hit EOF, and silently dropped everything
     * after it as one torn trailing record — SILENT truncation, not a thrown
     * error, which is why this test can only see the hazard in a corrupted
     * replay, not in an exception (see this task's report for the harness run
     * that reproduced it against the unfixed code, and the mutation-check run
     * that confirms this test discriminates).
     *
     * The fix serializes the header decision across instances (see
     * [FileJournal]'s KDoc, "Interleaving correctly, not refusing"): whichever
     * instance's [FileJournal.append] gets to the header check first writes
     * it, the other observes a non-empty file and writes none, and both then
     * append through their own `O_APPEND` descriptor exactly as two threads
     * on one instance already do (the test above). The two writers' records
     * therefore interleave correctly rather than being refused — chosen
     * because refusing a still-live second instance broke the "single
     * header" test below, which relies on exactly this kind of legitimate,
     * non-overlapping reuse.
     */
    @Test
    fun `two live instances on the same path never write two headers`() {
        val dir = createTempDirectory("fj-two-instances").toFile()
        val file = dir.resolve("host.journal")

        val perWriter = 50
        val writers = (0 until 2).map { w ->
            Thread {
                val journal = FileJournal(file)
                repeat(perWriter) { journal.append(rec("w$w-$it")) }
            }
        }
        writers.forEach { it.start() }
        writers.forEach { it.join(60_000) }

        // Exactly one header, at offset 0 — never a second MAGIC spliced mid-stream.
        file.readBytes().take(FileJournal.MAGIC.size).toByteArray() shouldBe FileJournal.MAGIC

        val replayed = FileJournal(file).replay().map { String(it) }
        replayed.size shouldBe 2 * perWriter
        replayed.toSet().size shouldBe 2 * perWriter
        assertTrue(
            replayed.all { it.matches(Regex("""w[01]-\d+""")) },
            "a duplicate header decoded as a garbage record, corrupting replay: " +
                "${replayed.filterNot { it.matches(Regex("""w[01]-\d+""")) }}",
        )
    }
}
