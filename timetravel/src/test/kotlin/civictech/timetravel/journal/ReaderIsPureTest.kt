package civictech.timetravel.journal

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-level guards on the reader (TTD1 F1, feature D6). A Gradle Test task's working
 * directory is the project directory, so paths resolve under `timetravel/`.
 */
class ReaderIsPureTest {

    private fun lines(files: List<File>): List<Pair<String, String>> =
        files.flatMap { f -> f.readLines().map { "${f.path}: $it" to it } }

    /**
     * `[TTD1-14]`: reading constructs no host, scheduler or cell — so the reader package may
     * not even import the host types, and its only `civictech.cell.host` imports are the
     * kernel's record-decoding seam.
     */
    @Test
    fun `TTD1-14 the journal package imports no host machinery`() {
        val files = File("src/main/kotlin/civictech/timetravel/journal")
            .listFiles { f -> f.isFile && f.extension == "kt" }!!.toList()
        files.size shouldBeGreaterThanOrEqual 4 // non-vacuity

        val all = lines(files)
        val forbidden = Regex("""^import civictech\.cell\.host\.(ManagedHost|SimulationController|HostScheduler|LocationRegistry)\b""")
        withClue("forbidden host imports") { all.filter { forbidden.containsMatchIn(it.second) }.shouldBeEmpty() }

        val hostImports = all.filter { it.second.startsWith("import civictech.cell.host.") }
        val allowed = Regex("""^import civictech\.cell\.host\.(JournalRecords|DecodedJournalRecord)$""")
        withClue("civictech.cell.host imports other than the decoding seam") {
            hostImports.filterNot { allowed.matches(it.second.trim()) }.shouldBeEmpty()
        }
    }

    /**
     * `[TTD1-02]`: the journal framing and record type bytes have one definition, in the kernel.
     * `RECORD_UNDESERIALIZABLE` is exempt: it is a `Reason` member (the module task's
     * `Reason.kt`), not a kernel type-byte constant — every kernel constant is `RECORD_` followed
     * by a record-type name, so the exemption hides none of them.
     */
    @Test
    fun `TTD1-02 no framing or record-type literal anywhere in timetravel main`() {
        val files = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        files.size shouldBeGreaterThanOrEqual 4 // non-vacuity
        val literal = Regex("""RECORD_(?!UNDESERIALIZABLE)|MAGIC|readInt\(""")
        withClue("framing literals under timetravel/src/main") {
            lines(files).filter { literal.containsMatchIn(it.second) }.map { it.first }.shouldBeEmpty()
        }
    }
}
