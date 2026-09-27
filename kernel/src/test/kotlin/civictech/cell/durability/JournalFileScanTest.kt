package civictech.cell.durability

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * computenet-wzbww D2 (the kernel half of `[TTD1-11]`): [scanJournalFile] reads a file
 * journal without refusing a foreign version and without dropping a torn tail, while
 * listing exactly what [FileJournal.replay] lists whenever replay would not throw.
 */
class JournalFileScanTest {

    @TempDir
    lateinit var dir: File

    private val r1 = byteArrayOf(1, 10, 11)
    private val r2 = byteArrayOf(2, 20)
    private val r3 = byteArrayOf(3, 30, 31, 32)

    private fun threeRecords(formatVersion: Int = JOURNAL_FORMAT_VERSION): File {
        val file = File(dir, "j.bin")
        FileJournal(file, formatVersion).apply { append(r1); append(r2); append(r3) }
        return file
    }

    /** Header (8) + `4+3` + `4+2`: where the third record's length prefix begins. */
    private val thirdPrefixAt = 8L + 7 + 6

    private fun List<ByteArray>.shouldMatch(other: List<ByteArray>) {
        size shouldBe other.size
        zip(other).forEach { (a, b) -> a.contentEquals(b) shouldBe true }
    }

    @Test
    fun `(a) an intact file lists replay's records with no tear`() {
        val file = threeRecords()
        val scan = scanJournalFile(file)

        scan.declaredFormatVersion shouldBe JOURNAL_FORMAT_VERSION
        scan.records.shouldMatch(FileJournal(file).replay())
        scan.records.shouldMatch(listOf(r1, r2, r3))
        scan.tear shouldBe null
    }

    @Test
    fun `(b) a partial length prefix is reported as a tear while replay drops it`() {
        val file = threeRecords()
        RandomAccessFile(file, "rw").use { it.setLength(thirdPrefixAt + 2) }
        val scan = scanJournalFile(file)

        scan.records.shouldMatch(listOf(r1, r2))
        scan.tear shouldBe JournalFileScan.Tear(1, 2)
        FileJournal(file).replay().size shouldBe 2
    }

    @Test
    fun `(c) a short payload is reported as a tear while replay drops it`() {
        val file = threeRecords()
        RandomAccessFile(file, "rw").use { it.setLength(thirdPrefixAt + 4 + 1) }
        val scan = scanJournalFile(file)

        scan.records.shouldMatch(listOf(r1, r2))
        scan.tear shouldBe JournalFileScan.Tear(1, 5)
        FileJournal(file).replay().size shouldBe 2
    }

    @Test
    fun `(d) a foreign declared version is reported, not refused`() {
        val file = threeRecords(formatVersion = 7)
        val scan = scanJournalFile(file)

        scan.declaredFormatVersion shouldBe 7
        scan.records.shouldMatch(listOf(r1, r2, r3))
        scan.tear shouldBe null
        shouldThrow<JournalFormatMismatch> { FileJournal(file).replay() }
    }

    @Test
    fun `(e) a pre-versioning journal reads as PRE_VERSIONING_FORMAT_VERSION`() {
        val file = File(dir, "prechange.bin")
        javaClass.getResourceAsStream("/civictech/cell/durability/prechange-journal.bin")!!
            .use { input -> file.outputStream().use { input.copyTo(it) } }
        val scan = scanJournalFile(file)

        scan.declaredFormatVersion shouldBe PRE_VERSIONING_FORMAT_VERSION
        scan.records.size shouldBe 4
        scan.records.shouldMatch(FileJournal(file).replay())
        scan.tear shouldBe null
    }

    @Test
    fun `(f) an empty file and a MAGIC-only file`() {
        val empty = File(dir, "empty.bin").apply { writeBytes(byteArrayOf()) }
        scanJournalFile(empty) shouldBe JournalFileScan(null, emptyList(), null)

        val magicOnly = File(dir, "magic.bin").apply { writeBytes(FileJournal.MAGIC) }
        scanJournalFile(magicOnly) shouldBe JournalFileScan(null, emptyList(), JournalFileScan.Tear(-1, 4))
    }

    @Test
    fun `(g) a path that is not a readable regular file is refused and nothing is created`() {
        shouldThrow<IllegalArgumentException> { scanJournalFile(File(dir, "absent/sub/j.bin")) }
        File(dir, "absent").exists() shouldBe false
        shouldThrow<IllegalArgumentException> { scanJournalFile(dir) }
    }

    @Test
    fun `(h) scanning leaves the file byte-for-byte and mtime unchanged`() {
        val file = threeRecords()
        file.setLastModified(1_000_000_000_000L)
        fun sha(): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        val before = Triple(file.length(), file.lastModified(), sha())

        scanJournalFile(file)

        Triple(file.length(), file.lastModified(), sha()) shouldBe before
        dir.list()!!.toSet() shouldBe setOf("j.bin")
    }
}
