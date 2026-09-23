package civictech.cell.durability

import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/**
 * `[KBLK-01]`: every [Journal] declares its [DurabilityClass] as a constant
 * readable on a fresh instance, without appending to or replaying the log.
 * `[KBLK-02]`: [FileJournal] declares [DurabilityClass.SYNCHRONOUS] and
 * [InMemoryJournal] declares [DurabilityClass.IN_MEMORY], with no other
 * behaviour change — [FileJournalHandleTest], [JournalFormatVersionTest] and
 * [JournalCompatibilityTest] remain the full regression for that half.
 */
class DurabilityClassTest {

    @Test
    fun `FileJournal declares SYNCHRONOUS on a fresh instance, before the file exists`() {
        val dir = createTempDirectory("durability-class").toFile()
        val file = dir.resolve("fresh.journal")

        file.exists() shouldBe false
        val journal = FileJournal(file)
        journal.durability shouldBe DurabilityClass.SYNCHRONOUS
        // the read above must not have touched the log itself
        file.exists() shouldBe false

        journal.append("record".toByteArray())
        journal.durability shouldBe DurabilityClass.SYNCHRONOUS
    }

    @Test
    fun `InMemoryJournal declares IN_MEMORY on a fresh instance and after appends`() {
        val journal = InMemoryJournal()
        journal.durability shouldBe DurabilityClass.IN_MEMORY

        journal.append("record".toByteArray())
        journal.durability shouldBe DurabilityClass.IN_MEMORY
    }

    /**
     * The [FileJournalHandleTest] shape, pinned here too: declaring
     * [FileJournal.durability] must not disturb append/replay behaviour — a
     * second [FileJournal] on the same path still sees everything the first
     * wrote.
     */
    @Test
    fun `FileJournal behaviour is unchanged - append then replay from a second instance`() {
        val dir = createTempDirectory("durability-class-behaviour").toFile()
        val file = dir.resolve("behaviour.journal")

        val writer = FileJournal(file)
        writer.append("alpha".toByteArray())
        writer.append("beta".toByteArray())
        writer.append("gamma".toByteArray())

        FileJournal(file).replay().map { String(it) } shouldBe listOf("alpha", "beta", "gamma")
    }

    /**
     * Compile-shape check for `[KBLK-01]`'s "abstract, no default": this
     * anonymous implementation must declare [Journal.durability] itself, with
     * nothing to inherit. Mutation for the reviewer: comment out
     * `override val durability` in [InMemoryJournal] (or here) and
     * `:kernel:compileKotlin` fails, because the interface member has no
     * default to fall back on.
     */
    private val declaresDurabilityItself: Journal = object : Journal {
        override val durability: DurabilityClass = DurabilityClass.IN_MEMORY
        override fun append(record: ByteArray) = Unit
        override fun replay(): List<ByteArray> = emptyList()
        override fun reset(records: List<ByteArray>) = Unit
    }

    @Test
    fun `the compile-shape stub declares its durability`() {
        declaresDurabilityItself.durability shouldBe DurabilityClass.IN_MEMORY
    }
}
