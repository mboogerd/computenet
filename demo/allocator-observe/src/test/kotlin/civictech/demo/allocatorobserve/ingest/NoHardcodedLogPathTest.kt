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
 * It is a lexical scan of the files the spend-log and run-dir paths travel
 * through, not the whole module: this package (`ingest/` — `SpendLogIngester`,
 * [OffsetCheckpoint], `SpendLogTailReader`), whose only sources of a path are
 * its constructor parameters, and `AllocatorObserveApp.kt`, where
 * `parseArgs` builds `AllocatorObserveConfig` — the one place a "quick local
 * run" default for `logPath` or `runDir` would be pasted. A string literal
 * elsewhere in the module — an HTTP route in `http/AllocatorRoutes.kt`, say —
 * is not a spend-log path and is none of this guard's business (filed and
 * narrowed as `computenet-fpml.6`; before that fix this scanned the whole
 * module and tripped on ordinary route literals). `AllocatorObserveApp.kt`
 * also carries the `/events` route, so a route literal there still trips the
 * scan; that file is kept in scope because dropping it would let the config
 * default this test exists to prevent pass unseen. Within its scope it catches
 * the shape the mistake actually takes — a string literal that is an absolute
 * path, a home-relative path, a Windows path, or a `.jsonl` file name. It does
 * not catch a path assembled from fragments at runtime, and it is not meant
 * to.
 */
class NoHardcodedLogPathTest {

    /** String literals that look like a filesystem location a deployment would have to override. */
    private val suspiciousLiteral =
        Regex(""""(?:/|~/|\.{1,2}/|[A-Za-z]:\\)[^"]*"|"[^"]*\.jsonl"""")

    private fun mainSources(): List<Path> {
        // Gradle runs tests with the module directory as the working directory;
        // an IDE or a repo-root invocation may not. Scoped to the files the
        // log and run-dir paths travel through (see the KDoc), not the module.
        val candidates =
            listOf(
                Path.of("src/main/kotlin/civictech/demo/allocatorobserve"),
                Path.of("demo/allocator-observe/src/main/kotlin/civictech/demo/allocatorobserve"),
            )
        val root =
            candidates.firstOrNull { Files.isDirectory(it.resolve("ingest")) }
                ?: error(
                    "cannot locate this module's ingest sources from working directory " +
                        "${Path.of("").toAbsolutePath()}; tried $candidates",
                )
        // Named explicitly so a rename fails the scan instead of silently
        // dropping the config's parse site out of it.
        val app = root.resolve("AllocatorObserveApp.kt")
        check(app.isRegularFile()) { "expected the config's parse site at $app" }
        val ingest =
            Files.walk(root.resolve("ingest")).use { stream ->
                stream.filter { it.isRegularFile() && it.extension == "kt" }.toList()
            }
        return ingest + app
    }

    @Test
    fun `no source on the log path's route names a concrete log path`() {
        val sources = mainSources()
        // Guard against the scan silently passing because it found nothing to
        // scan — the three ingest files plus the app, and if it ever finds
        // fewer, that is the bug rather than a pass.
        (sources.size >= 4) shouldBe true

        val offenders =
            sources.flatMap { file ->
                file.readLines().withIndex().flatMap { (index, text) ->
                    suspiciousLiteral.findAll(text).map { "$file:${index + 1}: ${it.value}" }
                }
            }

        offenders.shouldBeEmpty()
    }
}
