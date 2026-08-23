package civictech.demo.allocatorobserve.ingest

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

/**
 * The configuration half of the feature's rule 5 (`computenet-fpml.1`, design
 * note fpml.1-D1): the spend log's location and the checkpoint's run directory
 * reach the ingester only as parameters, and **no production source under this
 * module names a concrete log path**.
 *
 * This is checkable rather than merely stated because the pressure is real: no
 * socaity spend log exists yet (verified 2026-08-23 on the epic) and its
 * eventual location is undecided, because it syncs over the beads/dolt channel.
 * The first person to want a quick local run is the one who would paste a path
 * into a default, and a default is exactly what a later deployment cannot
 * override without noticing.
 *
 * ## What it can and cannot catch
 *
 * It is a lexical scan of the module's `src/main/kotlin`, so it catches the
 * shape the mistake actually takes — a string literal that is an absolute path,
 * a home-relative path, a Windows path, or a `.jsonl` file name. It does not
 * catch a path assembled from fragments at runtime, and it is not meant to: the
 * structural guarantee is that [SpendLogIngester]'s only sources of a path are
 * its `logPath` and `runDir` constructor parameters, and this test guards the
 * one way that guarantee gets quietly walked back.
 */
class NoHardcodedLogPathTest {

    /** String literals that look like a filesystem location a deployment would have to override. */
    private val suspiciousLiteral =
        Regex(""""(?:/|~/|\.{1,2}/|[A-Za-z]:\\)[^"]*"|"[^"]*\.jsonl"""")

    private fun mainSources(): List<Path> {
        // Gradle runs tests with the module directory as the working directory;
        // an IDE or a repo-root invocation may not.
        val candidates =
            listOf(
                Path.of("src/main/kotlin"),
                Path.of("demo/allocator-observe/src/main/kotlin"),
            )
        val root =
            candidates.firstOrNull { Files.isDirectory(it) }
                ?: error(
                    "cannot locate this module's main sources from working directory " +
                        "${Path.of("").toAbsolutePath()}; tried $candidates",
                )
        return Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "kt" }.toList()
        }
    }

    @Test
    fun `no production source under this module names a concrete log path`() {
        val sources = mainSources()
        // Guard against the scan silently passing because it found nothing to
        // scan — the module has main sources, and if it ever does not, that is
        // the bug rather than a pass.
        (sources.size >= 3) shouldBe true

        val offenders =
            sources.flatMap { file ->
                file.readLines().withIndex().flatMap { (index, text) ->
                    suspiciousLiteral.findAll(text).map { "$file:${index + 1}: ${it.value}" }
                }
            }

        offenders.shouldBeEmpty()
    }
}
