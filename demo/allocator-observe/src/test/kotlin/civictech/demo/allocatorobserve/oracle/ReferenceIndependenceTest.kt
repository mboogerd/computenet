package civictech.demo.allocatorobserve.oracle

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readLines

/**
 * Rule 5 of feature `computenet-fpml.5`, made checkable: **the reference fold
 * and the comparator import no type from the implementation they check**
 * (design entry fpml.5-D6).
 *
 * The pressure this guards is not hypothetical. [ReferenceReport] re-implements
 * a timeline, a session ledger and a rounding policy that already exist a few
 * packages away, and reusing one of them would look like removing duplication.
 * It would instead dissolve the oracle: a differential test whose two sides
 * share a definition cannot detect a mistake in that definition. The check has
 * to be lexical because the mistake is a one-line import, and nothing else about
 * the code would change.
 *
 * ## Shape, and why this shape
 *
 * `:oracle`'s `ModuleDependencyTest` is the precedent — there, independence is a
 * module boundary, so the check reads build files. Here both sides live in one
 * module (the reference is test-source of the module under test), so there is no
 * dependency edge to inspect and the transposition is an import scan over this
 * package's non-test sources.
 *
 * A lexical scan can pass by scanning nothing, so the non-vacuity control below
 * asserts what it actually read: both files by name, and at least one real
 * import line found in them.
 */
class ReferenceIndependenceTest {

    /** The forbidden prefix: any type of the module under test, root package or sub-package. */
    private val forbiddenImport = Regex("""^import civictech\.demo\.allocatorobserve""")

    private fun oracleSources(): List<Path> {
        // Gradle runs tests with the module directory as the working directory;
        // an IDE or a repo-root invocation may not.
        val candidates =
            listOf(
                Path.of("src/test/kotlin/civictech/demo/allocatorobserve/oracle"),
                Path.of("demo/allocator-observe/src/test/kotlin/civictech/demo/allocatorobserve/oracle"),
            )
        val root =
            candidates.firstOrNull { Files.isDirectory(it) }
                ?: error(
                    "cannot locate this package's sources from working directory " +
                        "${Path.of("").toAbsolutePath()}; tried $candidates",
                )
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.name.endsWith(".kt") && !it.name.endsWith("Test.kt") }
                .toList()
        }
    }

    @Test
    fun `the reference fold and comparator import nothing from the implementation`() {
        val sources = oracleSources()

        // Non-vacuity: the scan must have read the two files whose independence
        // is the acceptance criterion, and must have seen real import lines in
        // them. A scan that found no files, or files it could not read, passes
        // an import check trivially.
        val names = sources.map { it.name }.toSet()
        names.contains("ReferenceReport.kt") shouldBe true
        names.contains("ReportComparison.kt") shouldBe true
        val serializationImports =
            sources.filter { it.name == "ReferenceReport.kt" || it.name == "ReportComparison.kt" }
                .flatMap { it.readLines() }
                .count { it.startsWith("import kotlinx.serialization.json") }
        (serializationImports > 0) shouldBe true

        val offenders =
            sources.flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, text) -> forbiddenImport.containsMatchIn(text) }
                    .map { (index, text) -> "${file.name}:${index + 1}: ${text.trim()}" }
            }

        offenders.shouldBeEmpty()
    }
}
