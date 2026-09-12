package civictech.demo.beadsmirror.writeback

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files

/**
 * Acceptance criterion 8 of computenet-6wc.1.1: `civictech.demo.beadsmirror.writeback`'s main
 * sources carry no `ProcessBuilder`/`Runtime.exec` — this task is the write-back applier's PURE
 * half, and this is the guard that keeps it that way once the sibling applier
 * (computenet-6wc.1.3) lands its own executing code beside it.
 *
 * A source-text scan, not ArchUnit — same choice and same reasoning as
 * `civictech.oracle.model`'s `ModelImportBoundaryTest`. `BdImport.kt` (the applier's own import
 * runner, not yet written) is exempted **by file name**: it is this package's one deliberately
 * impure file, and the bead that names it also names this exemption, so a scan that did not
 * exempt it would have to be edited the moment that sibling task lands rather than staying a
 * standing guard against every OTHER file drifting impure.
 */
object WriteBackPurityScanner {

    data class Violation(val file: String, val match: String)

    private const val EXEMPT_FILE_NAME = "BdImport.kt"

    private val forbidden = listOf("ProcessBuilder", "Runtime.exec", "Runtime.getRuntime().exec")

    /** Pure over source text: whether [text] is clean depends only on its own content. */
    fun scanText(fileLabel: String, text: String): List<Violation> =
        forbidden.filter { text.contains(it) }.map { Violation(fileLabel, it) }

    /**
     * Scans every `.kt` file directly and transitively under [dir], skipping [EXEMPT_FILE_NAME]
     * by name. Fails loudly — never an empty, vacuously "clean" result — when [dir] is missing or
     * holds no `.kt` file, so a moved or renamed `writeback` source set cannot make the positive
     * test below pass for the wrong reason.
     */
    fun scanDirectory(dir: File): List<Violation> {
        check(dir.isDirectory) {
            "writeback source directory does not exist: ${dir.absolutePath} " +
                "— cannot verify the pure-half guard against an absent source set."
        }
        val ktFiles = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != EXEMPT_FILE_NAME }
            .toList()
        check(ktFiles.isNotEmpty()) {
            "writeback source directory has no non-exempt .kt files: ${dir.absolutePath} " +
                "— cannot verify the pure-half guard against zero files."
        }
        return ktFiles.flatMap { scanText(it.path, it.readText()) }
    }
}

class WriteBackPurityTest {

    /** The permanent proof the scanner CAN fail: a synthetic file spawning a subprocess is flagged. */
    @Test
    fun `a synthetic writeback source invoking ProcessBuilder is flagged`() {
        val tempDir = Files.createTempDirectory("writeback-purity-negative").toFile()
        try {
            val offender = File(tempDir, "Offender.kt")
            offender.writeText(
                """
                package civictech.demo.beadsmirror.writeback

                class Offender {
                    fun run() = ProcessBuilder("bd", "import").start()
                }
                """.trimIndent(),
            )

            val violations = WriteBackPurityScanner.scanDirectory(tempDir)

            violations shouldBe listOf(WriteBackPurityScanner.Violation(offender.path, "ProcessBuilder"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** `Runtime.exec` is flagged the same way, independent of `ProcessBuilder`. */
    @Test
    fun `a synthetic writeback source invoking Runtime-exec is flagged`() {
        val tempDir = Files.createTempDirectory("writeback-purity-runtime-exec").toFile()
        try {
            val offender = File(tempDir, "Offender.kt")
            offender.writeText(
                """
                package civictech.demo.beadsmirror.writeback

                class Offender {
                    fun run() = Runtime.getRuntime().exec(arrayOf("bd", "import"))
                }
                """.trimIndent(),
            )

            val violations = WriteBackPurityScanner.scanDirectory(tempDir)

            violations shouldBe listOf(
                WriteBackPurityScanner.Violation(offender.path, "Runtime.getRuntime().exec"),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** `BdImport.kt` — the applier's own import runner — is exempt by name, even when it spawns a subprocess. */
    @Test
    fun `a BdImport-kt file is exempt from the scan by name`() {
        val tempDir = Files.createTempDirectory("writeback-purity-exempt").toFile()
        try {
            File(tempDir, "BdImport.kt").writeText(
                """
                package civictech.demo.beadsmirror.writeback

                class BdImport {
                    fun run() = ProcessBuilder("bd", "import").start()
                }
                """.trimIndent(),
            )
            // A non-exempt file must still exist for scanDirectory to run at all — its own
            // "no files" guard would otherwise make this test pass vacuously.
            File(tempDir, "Innocent.kt").writeText(
                """
                package civictech.demo.beadsmirror.writeback

                class Innocent
                """.trimIndent(),
            )

            val violations = WriteBackPurityScanner.scanDirectory(tempDir)

            violations.shouldBeEmpty()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `an absent writeback source directory fails loudly rather than passing vacuously`() {
        val parent = Files.createTempDirectory("writeback-purity-missing").toFile()
        try {
            val missing = File(parent, "does-not-exist")

            val failure = assertThrows<IllegalStateException> {
                WriteBackPurityScanner.scanDirectory(missing)
            }
            failure.message!!.contains("does not exist") shouldBe true
        } finally {
            parent.deleteRecursively()
        }
    }

    /**
     * The positive gate: the real `civictech.demo.beadsmirror.writeback` main source set is clean
     * today. A Gradle Test task's working directory is the project directory, so this resolves to
     * `demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/writeback`.
     */
    @Test
    fun `the real writeback main source set carries no ProcessBuilder or Runtime-exec`() {
        val writebackSourceDir = File("src/main/kotlin/civictech/demo/beadsmirror/writeback")

        val violations = WriteBackPurityScanner.scanDirectory(writebackSourceDir)

        withClue("civictech.demo.beadsmirror.writeback main sources are not pure: $violations") {
            violations.shouldBeEmpty()
        }
    }
}
