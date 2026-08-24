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
}
