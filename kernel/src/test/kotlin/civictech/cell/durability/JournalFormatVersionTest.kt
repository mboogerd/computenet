package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.host.ManagedHost
import civictech.cell.host.RecoveryIncomplete
import civictech.cell.host.SimulationController
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.io.Serializable
import java.util.UUID

/**
 * computenet-437w — **a journal declares the format generation it was written
 * under, and replay refuses another one by name.**
 *
 * The defect this closes: [Journal] carried no version at all, so any change to a
 * cell's persisted state shape broke replay of an older journal *silently* — the
 * failure surfaced as a deserialization or cast error from whichever cell's
 * `restore` the replay happened to reach first, with nothing anywhere saying
 * "this journal predates the running code". The unfixed failure, quoted from the
 * run that established it, is:
 *
 * ```
 * civictech.cell.host.RecoveryIncomplete: journal replay aborted at record 0 of 1:
 *   class java.util.HashSet cannot be cast to class java.util.List
 *   (java.util.HashSet and java.util.List are in module java.base of loader 'bootstrap')
 * ```
 *
 * That failure is *reproduced* here, not replaced — see
 * [a journal replayed into a changed state shape WITHOUT a version bump still fails inside a cell's restore].
 * The version is a contract a change has to opt into by bumping
 * [JOURNAL_FORMAT_VERSION]; what this bead buys is that the bump is now *possible*
 * and *checked*, not that a forgotten bump is detected. Nothing here can detect a
 * forgotten one: on-disk shape is a property of arbitrary `Stateful.snapshot`
 * implementations, which no constant can observe.
 *
 * **The policy, stated in [Journal]'s KDoc and asserted here: refuse.** Journals
 * are not migrated across format versions.
 *
 * ## Standing in for a build at another version
 *
 * A test cannot recompile the kernel with a bumped [JOURNAL_FORMAT_VERSION], so the
 * two builds are stood in for by two [FileJournal] instances over the same file at
 * different `formatVersion`s: the writer is the build before the shape change, the
 * reader the build that changed the shape and bumped the constant with it. That is
 * a simulation of the version dispatch, and it is exact for everything the header
 * governs — the bytes on disk, the comparison, the refusal — while leaving the
 * developer's obligation to actually bump the constant outside what any test checks.
 */
class JournalFormatVersionTest {

    companion object {
        val CELL = CellRef(UUID.fromString("00000000-0000-4000-8000-0000000004a1"))

        /** The build that changed a persisted shape and bumped the constant with it. */
        const val NEXT_VERSION: Int = JOURNAL_FORMAT_VERSION + 1
    }

    /**
     * Stands in for the `computenet-vvre` shape change that filed this bead:
     * IntersectSetCell's ledger slot went from a bare set to `[set, counter]`, so a
     * journal written by the previous code fails the new `restore`. Both shapes live
     * here at once because one test run cannot host two builds.
     */
    enum class Shape { V1, V2 }

    class ShapeShiftingCell(
        override val ref: CellRef,
        private val shape: Shape,
        initial: Set<String> = emptySet(),
    ) : Cell, Stateful {
        var items: Set<String> = initial
        var restored: Boolean = false

        override fun snapshot(): Serializable = when (shape) {
            Shape.V1 -> HashMap<String, Serializable>(mapOf("ledger" to HashSet(items)))
            Shape.V2 -> HashMap<String, Serializable>(
                mapOf("ledger" to ArrayList<Serializable>(listOf(HashSet(items), 0L)))
            )
        }

        @Suppress("UNCHECKED_CAST")
        override fun restore(state: Serializable) {
            restored = true
            val slot = (state as Map<String, Any?>)["ledger"]
            items = when (shape) {
                Shape.V1 -> slot as Set<String>
                Shape.V2 -> (slot as List<Any?>)[0] as Set<String> // ClassCastException on a V1 blob
            }
        }
    }

    /** Writes a checkpointed journal holding [shape]'s snapshot of [items]. */
    private fun writeJournal(journal: Journal, shape: Shape, items: Set<String>) {
        val controller = SimulationController(seed = 437)
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(ShapeShiftingCell(CELL, shape, items))
        controller.runToIdle()
        host.checkpoint(journal)
    }

    /** Replays [journal] into a graph rebuilt at [shape]; returns the cell it was rebuilt with. */
    private fun recoverInto(journal: Journal, shape: Shape): ShapeShiftingCell {
        val controller = SimulationController(seed = 438)
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val cell = ShapeShiftingCell(CELL, shape)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        host.recoverFrom(journal)
        controller.runToIdle()
        return cell
    }

    /**
     * **The headline.** A journal written under one state shape, replayed by a build
     * that changed the shape and bumped [JOURNAL_FORMAT_VERSION] with it, is refused
     * with a message naming both versions — and refused *before* a record is decoded,
     * so no cell's `restore` is reached and the refusal cannot be a cast error in
     * disguise.
     */
    @Test
    fun `a journal from an older format version is refused by name, before any cell restore runs`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "host.journal")
        writeJournal(FileJournal(file), Shape.V1, setOf("apple", "banana"))

        val reader = FileJournal(file, formatVersion = NEXT_VERSION)
        val refusal = assertThrows<JournalFormatMismatch> { reader.replay() }

        refusal.found shouldBe JOURNAL_FORMAT_VERSION
        refusal.expected shouldBe NEXT_VERSION
        refusal.message!! shouldContain "journal format version mismatch"
        refusal.message!! shouldContain "written under journal format version $JOURNAL_FORMAT_VERSION"
        refusal.message!! shouldContain "this build reads version $NEXT_VERSION"
        // the stated policy, in the refusal itself: no migration, discard or downgrade
        refusal.message!! shouldContain "NOT migrated"

        // and through the host: recoverFrom surfaces the refusal itself, NOT a
        // RecoveryIncomplete — replay() refuses before the per-record loop exists to
        // wrap it, which is what makes the version mismatch the message a reader sees
        val controller = SimulationController(seed = 439)
        val host = ManagedHost(scheduler = controller.scheduler(), journal = reader)
        val cell = ShapeShiftingCell(CELL, Shape.V2)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        assertThrows<JournalFormatMismatch> { host.recoverFrom(reader) }
        // no record was decoded, so the cell's restore was never reached
        cell.restored shouldBe false
    }

    /**
     * **The boundary, asserted rather than argued away.** The version is a contract a
     * change opts into. A build that changes a persisted shape and *forgets* to bump
     * [JOURNAL_FORMAT_VERSION] still fails exactly the way this bead was filed about —
     * inside a cell's `restore`, wrapped in [RecoveryIncomplete], naming no version.
     * Nothing in this change detects a forgotten bump, and this test says so out loud
     * so a later reader does not mistake the header for a shape checksum.
     */
    @Test
    fun `a journal replayed into a changed state shape WITHOUT a version bump still fails inside a cell's restore`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "host.journal")
        writeJournal(FileJournal(file), Shape.V1, setOf("apple", "banana"))

        val failure = assertThrows<RecoveryIncomplete> { recoverInto(FileJournal(file), Shape.V2) }
        failure.cause.shouldBeInstanceOf<ClassCastException>()
        failure.message!! shouldContain "journal replay aborted at record 0 of 1"
        // the point: no version anywhere in it — this is the failure the header replaces
        // when, and only when, the shape change bumps the constant
        failure.message!!.contains("version") shouldBe false
    }

    /** A journal written and read by one build round-trips, header and all. */
    @Test
    fun `a journal round-trips at the current format version`(@TempDir dir: File) {
        val file = File(dir, "host.journal")
        writeJournal(FileJournal(file), Shape.V1, setOf("apple", "banana"))

        // the header really is on disk: MAGIC then the big-endian version
        val head = file.readBytes().copyOfRange(0, 8)
        head.copyOfRange(0, 4).contentEquals(FileJournal.MAGIC).shouldBeTrue()
        java.io.DataInputStream(head.copyOfRange(4, 8).inputStream()).readInt() shouldBe JOURNAL_FORMAT_VERSION

        val recovered = recoverInto(FileJournal(file), Shape.V1)
        recovered.restored.shouldBeTrue()
        recovered.items shouldBe setOf("apple", "banana")
    }

    /**
     * The additive half: a journal written before versioning existed has no header,
     * and is read as [PRE_VERSIONING_FORMAT_VERSION] rather than refused. Simulated by
     * stripping the header off a journal this build wrote — the checked-in
     * `prechange-journal.bin` fixture is the real article, and
     * [JournalCompatibilityTest] replays it unmodified through [FileJournal].
     */
    @Test
    fun `an unversioned journal is read as the pre-versioning generation`(@TempDir dir: File) {
        val versioned = File(dir, "host.journal")
        writeJournal(FileJournal(versioned), Shape.V1, setOf("apple", "banana"))

        val legacy = File(dir, "legacy.journal")
        legacy.writeBytes(versioned.readBytes().copyOfRange(8, versioned.length().toInt()))
        legacy.readBytes().copyOfRange(0, 4).contentEquals(FileJournal.MAGIC) shouldBe false

        PRE_VERSIONING_FORMAT_VERSION shouldBe JOURNAL_FORMAT_VERSION
        recoverInto(FileJournal(legacy), Shape.V1).items shouldBe setOf("apple", "banana")

        // and it is not exempt from the check — a later build refuses it too, naming
        // the generation it is assumed to belong to
        val refusal = assertThrows<JournalFormatMismatch> { FileJournal(legacy, NEXT_VERSION).replay() }
        refusal.found shouldBe PRE_VERSIONING_FORMAT_VERSION
        refusal.expected shouldBe NEXT_VERSION
    }

    /** An absent or empty journal is not a version mismatch: there is nothing to refuse. */
    @Test
    fun `an absent or empty journal replays empty rather than refusing`(@TempDir dir: File) {
        FileJournal(File(dir, "absent.journal"), NEXT_VERSION).replay() shouldBe emptyList()
        val empty = File(dir, "empty.journal").also { it.createNewFile() }
        FileJournal(empty, NEXT_VERSION).replay() shouldBe emptyList()

        // and an empty file acquires the writer's header on its first append
        val journal = FileJournal(empty, NEXT_VERSION)
        journal.append(byteArrayOf(9, 9))
        journal.replay().single().toList() shouldBe listOf<Byte>(9, 9)
        assertThrows<JournalFormatMismatch> { FileJournal(empty).replay() }
    }

    /**
     * **computenet-o2aj — the resolution chosen: refuse on the append side too, so the
     * mixed-version file the bead describes can never be constructed.**
     *
     * The bead allowed two resolutions: pin the mixed file's replay behaviour by test
     * (leaving it constructible), or make [FileJournal.append] refuse a mismatched
     * declared version (making it unconstructible). The reviewer's "benign" argument for
     * the first was that replay at the file's OWN declared version still reads every
     * record correctly and replay at any OTHER version already refuses before decoding —
     * true today because the frame encoding [JournalFile.write] uses is unchanged across
     * generations, but that is a fact about the current encoding, not a property the
     * header enforces. It would silently stop holding the day a version bump changed
     * what a length-prefixed frame's bytes are read as EITHER: a record appended by a
     * newer build, sitting after an older build's header. Refusing at append removes the
     * dependency on that fact entirely: this test's `mismatched.append(...)` call throws
     * before a single byte reaches the file, so no such record is ever on disk to matter.
     */
    @Test
    fun `append refuses a file whose header declares another version, before writing a byte`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "host.journal")
        FileJournal(file).append("first".toByteArray())

        val mismatched = FileJournal(file, formatVersion = NEXT_VERSION)
        val refusal = assertThrows<JournalFormatMismatch> { mismatched.append("second".toByteArray()) }
        refusal.found shouldBe JOURNAL_FORMAT_VERSION
        refusal.expected shouldBe NEXT_VERSION
        refusal.message!! shouldContain "journal format version mismatch"

        // nothing was written: the file holds exactly the one record from before,
        // and a second append at the file's own version still works afterwards
        FileJournal(file).replay().map { String(it) } shouldBe listOf("first")
        FileJournal(file).append("third".toByteArray())
        FileJournal(file).replay().map { String(it) } shouldBe listOf("first", "third")
    }

    /**
     * The pre-versioning generation is not exempt from the append-side check either —
     * the same asymmetry [an unversioned journal is read as the pre-versioning generation]
     * pins for replay, pinned here for append: appending at the pre-versioning generation
     * itself succeeds, appending at any OTHER generation is refused.
     */
    @Test
    fun `append refuses an unversioned file too, at any version other than the pre-versioning generation`(
        @TempDir dir: File,
    ) {
        val versioned = File(dir, "host.journal")
        FileJournal(versioned).append("first".toByteArray())
        val headerless = versioned.readBytes().copyOfRange(8, versioned.length().toInt())

        val legacy = File(dir, "legacy.journal").also { it.writeBytes(headerless) }
        legacy.readBytes().copyOfRange(0, 4).contentEquals(FileJournal.MAGIC) shouldBe false
        // pre-versioning generation == this build's default: append proceeds normally
        FileJournal(legacy).append("second".toByteArray())
        FileJournal(legacy).replay().map { String(it) } shouldBe listOf("first", "second")

        val legacy2 = File(dir, "legacy2.journal").also { it.writeBytes(headerless) }
        val refusal = assertThrows<JournalFormatMismatch> {
            FileJournal(legacy2, NEXT_VERSION).append("nope".toByteArray())
        }
        refusal.found shouldBe PRE_VERSIONING_FORMAT_VERSION
        refusal.expected shouldBe NEXT_VERSION
        // nothing was written: byte-identical to the untouched headerless copy
        legacy2.readBytes().toList() shouldBe headerless.toList()
    }

    /**
     * computenet-s710r — **a torn header is rewritten on append, not left to
     * corrupt the record after it.**
     *
     * `readAndCheckHeader`'s own comment names what a file holding [FileJournal.MAGIC]
     * with no version int means: "a crash inside the very first append, which
     * acknowledged nothing" — the same disposition as an absent file. Before this fix,
     * `sink()` treated that state as an ordinary non-empty file, ran the o2aj version
     * check, found nothing to compare (the check returns `null`, harmlessly, for this
     * exact shape) and let the append through with no header rewritten — so the append
     * landed directly after the torn `MAGIC` bytes. Replay then read `MAGIC`, tried to
     * read a version int from what was actually the appended record's length prefix,
     * and refused the file by a garbage "version" derived from the first few bytes of
     * the caller's own record.
     *
     * The choice recorded beside [Journal]'s append-side KDoc: rewrite rather than
     * refuse, because nothing here was ever acknowledged — the same disposition
     * `readAndCheckHeader` already gives an actually-empty file (fresh header on first
     * write) and [replay] already gives a torn TRAILING record (silently dropped, never
     * refused).
     */
    @Test
    fun `append rewrites a file holding only a torn header (MAGIC, no version)`(@TempDir dir: File) {
        val file = File(dir, "host.journal")
        file.writeBytes(FileJournal.MAGIC) // MAGIC written, crash before the version int landed

        FileJournal(file).append("first".toByteArray())

        FileJournal(file).replay().map { String(it) } shouldBe listOf("first")
        val head = file.readBytes().copyOfRange(0, 8)
        head.copyOfRange(0, 4).contentEquals(FileJournal.MAGIC).shouldBeTrue()
        java.io.DataInputStream(head.copyOfRange(4, 8).inputStream()).readInt() shouldBe JOURNAL_FORMAT_VERSION
    }

    /**
     * The other torn shape the bead names: a prefix shorter than [FileJournal.MAGIC]
     * itself, from a crash before even the magic bytes finished landing. Too short to
     * be any legitimate acknowledged record either (the smallest record needs a full
     * 4-byte length prefix first), so it gets the same rewrite.
     */
    @Test
    fun `append rewrites a file holding only a 1-3 byte prefix`(@TempDir dir: File) {
        val file = File(dir, "host.journal")
        file.writeBytes(FileJournal.MAGIC.copyOfRange(0, 2)) // crash before MAGIC itself finished landing

        FileJournal(file).append("first".toByteArray())

        FileJournal(file).replay().map { String(it) } shouldBe listOf("first")
    }

    /**
     * The acceptance criteria names [BatchedFileJournal] explicitly (o2aj's reviewer
     * noted the shared-check behavior was undocumented and untested for it): both
     * classes delegate every byte decision to the one [JournalFile] encoding, so the
     * torn-header rewrite applies here too, not just through [FileJournal].
     */
    @Test
    fun `BatchedFileJournal also rewrites a torn header rather than corrupting the append after it`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "host.journal")
        file.writeBytes(FileJournal.MAGIC)

        val batched = BatchedFileJournal(file, syncEvery = 1)
        batched.append("first".toByteArray())

        FileJournal(file).replay().map { String(it) } shouldBe listOf("first")
    }

    /**
     * The guard the torn-header rewrite must never cross: a pre-versioning file of 4-7
     * bytes CAN hold an acknowledged record — a single empty record is exactly its
     * 4-byte zero length prefix — so only a sub-4-byte file, or one whose first four
     * bytes are exactly [FileJournal.MAGIC], is torn. Truncating on length alone
     * (`< 8`) would silently delete this record on the next append.
     */
    @Test
    fun `append never rewrites a short pre-versioning file that holds an acknowledged record`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "legacy.journal")
        file.writeBytes(ByteArray(Int.SIZE_BYTES)) // one acknowledged empty record, no header

        FileJournal(file).append("second".toByteArray())

        FileJournal(file).replay().map { String(it) } shouldBe listOf("", "second")
    }

    /**
     * Both torn shapes — MAGIC plus a PARTIAL version int (5-7 bytes), and a partial
     * MAGIC (1-3 bytes) — through both journal classes. Every arm runs and each failure
     * is reported by name, so a red run says which shape and which class broke.
     */
    @Test
    fun `append rewrites MAGIC followed by a partial version int, and a partial MAGIC, through both journal classes`(
        @TempDir dir: File,
    ) {
        val shapes = (1..3).flatMap { extra ->
            listOf(
                "magic+$extra-version-bytes" to (FileJournal.MAGIC + ByteArray(extra)),
                "$extra-magic-bytes" to FileJournal.MAGIC.copyOfRange(0, extra),
            )
        }
        val appenders = listOf<Pair<String, (File) -> Unit>>(
            "FileJournal" to { f -> FileJournal(f).append("first".toByteArray()) },
            "BatchedFileJournal" to { f -> BatchedFileJournal(f, syncEvery = 1).append("first".toByteArray()) },
        )
        val failures = mutableListOf<String>()
        for ((shape, bytes) in shapes) {
            for ((cls, append) in appenders) {
                val file = File(dir, "$cls-$shape.journal").also { it.writeBytes(bytes) }
                runCatching {
                    append(file)
                    FileJournal(file).replay().map { String(it) } shouldBe listOf("first")
                }.onFailure { failures += "$cls/$shape: ${it::class.simpleName}" }
            }
        }
        failures shouldBe emptyList()
    }
}
