package civictech.demo.beadsmirror.writeback

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * computenet-6wc.1.4, structural halves of clauses 1 and 4: a source-text
 * scan over `civictech.demo.beadsmirror.writeback`'s main sources — same
 * text-scan approach as [WriteBackPurityTest], not ArchUnit — confirming:
 *
 * (i)  the STRING LITERAL `"--allow-stale"` (as it appears in a
 *      `ProcessBuilder` argument list, quoted exactly the way Kotlin source
 *      quotes a process argument) occurs in exactly one file, [BdImport],
 *      and that file's import entry point takes a single row (no
 *      `List<JsonObject>`/`Iterable<JsonObject>`/vararg-of-rows parameter
 *      anywhere in its text) — clause 1's structural half: bulk
 *      `--allow-stale` cannot be reintroduced by editing a signature,
 *      because there is only ever one row-shaped parameter to widen.
 * (ii) no writeback main source READS `tie_kept_local_ids`,
 *      `stale_skipped_ids` or `updated_issues` as a report key — the
 *      untrustworthy `bd import` report fields [BdImport]'s own KDoc and
 *      [WriteBackApplier]'s re-read mechanism say are never consulted
 *      (clause 4's structural half).
 *
 * **Deviation from the bead's literal wording, recorded here rather than
 * silently substituted — twice, same shape both times.** The bead's clause
 * (i) says to scan for the BARE literal `--allow-stale`; clause (ii) says to
 * scan for the bare literals `tie_kept_local_ids`/`stale_skipped_ids`/
 * `updated_issues`. Neither bare substring is unique to (i)'s intended file
 * or (ii)'s intended "never mentioned" today: `--allow-stale` also appears
 * twice in `WriteBackApplier.kt`'s own KDoc prose (backtick-quoted:
 * `` `bd import --allow-stale` ``, describing the mechanism in words, not
 * invoking it), and all three report-field names appear in `BdImport.kt`'s
 * OWN KDoc (backtick-quoted again: `` `tie_kept_local_ids` `` etc., in the
 * very paragraph explaining WHY nothing reads them) — two unlabelled clauses
 * that turned out false against the actual sources (`grep -rn -- '--allow-stale'`
 * and `grep -n 'tie_kept_local_ids\|stale_skipped_ids\|updated_issues'`
 * against `demo/beadsmirror/src/main/kotlin/.../writeback/`, 2026-09-13; the
 * second was caught by running this very test red before adjusting it — see
 * the `no writeback main source reads...` run below).
 *
 * Both are fixed the same way: Kotlin source spells an actual process
 * argument or a JSON-object key lookup as a DOUBLE-QUOTED string literal
 * (`"--allow-stale"`, `"tie_kept_local_ids"`), never as backtick-quoted KDoc
 * prose. Scanning for the double-quoted form greps to exactly one hit for
 * (i) — `BdImport.kt`'s `ProcessBuilder(...)` call — and zero hits for (ii)
 * across every writeback main file, which is what each clause is actually
 * trying to pin: that the flag is invoked from one place, and that no code
 * path indexes a report object by any of these keys. Reported on the bead's
 * thread as a breakdown-premise finding, per
 * `.claude/skills/work/references/task.md` step 3's "check it before you
 * build on it".
 *
 * Clause 4's "(iii) `ProcessBuilder` occurs only in `BdImport.kt`" is
 * ALREADY pinned by [WriteBackPurityTest] (computenet-6wc.1.1/.3's own
 * guard: every OTHER writeback main file is scanned for
 * `ProcessBuilder`/`Runtime.exec`, and `BdImport.kt` is the sole, named
 * exemption — see that test's `the real writeback main source set carries
 * no ProcessBuilder or Runtime-exec`). That file is outside this task's
 * `metadata.files` claim, so this test cites it rather than duplicating its
 * assertion.
 */
class WriteBackSourceGuardTest {

    private val writebackSourceDir = File("src/main/kotlin/civictech/demo/beadsmirror/writeback")

    /**
     * Every `.kt` file directly and transitively under the real writeback
     * main source set. Fails loudly rather than passing vacuously when the
     * directory is missing or empty — same guard [WriteBackPurityTest]'s
     * `scanDirectory` carries, for the same reason: a moved or renamed
     * source set must not make every positive assertion below pass for
     * having scanned nothing.
     */
    private fun sourceFiles(): List<File> {
        check(writebackSourceDir.isDirectory) {
            "writeback source directory does not exist: ${writebackSourceDir.absolutePath} " +
                "— cannot verify the source-scan guards against an absent source set."
        }
        val files = writebackSourceDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        check(files.isNotEmpty()) {
            "writeback source directory has no .kt files: ${writebackSourceDir.absolutePath} " +
                "— cannot verify the source-scan guards against zero files."
        }
        return files
    }

    @Test
    fun `the quoted --allow-stale flag literal appears in exactly one file, BdImport-kt`() {
        val files = sourceFiles()
        val withFlag = files.filter { it.readText().contains("\"--allow-stale\"") }

        withClue("files containing the quoted literal \"--allow-stale\": ${withFlag.map { it.name }}") {
            withFlag.map { it.name } shouldBe listOf("BdImport.kt")
        }
    }

    @Test
    fun `BdImport-kt's import entry point takes a single row, not a list, iterable or vararg of rows`() {
        val bdImportFile = sourceFiles().single { it.name == "BdImport.kt" }
        val text = bdImportFile.readText()

        val multiRowPatterns = listOf(
            Regex("List<\\s*JsonObject\\s*>"),
            Regex("Iterable<\\s*JsonObject\\s*>"),
            Regex("vararg\\s+\\w+\\s*:\\s*JsonObject"),
        )
        val found = multiRowPatterns.mapNotNull { it.find(text)?.value }

        withClue("BdImport.kt contains a multi-row-shaped signature fragment: $found") {
            found.shouldBeEmpty()
        }
    }

    @Test
    fun `no writeback main source reads the import report's tie or stale fields`() {
        // Double-quoted: how a JSON-key lookup is actually spelled in Kotlin
        // source, as opposed to backtick-quoted KDoc prose explaining why the
        // key is never read (BdImport.kt's own KDoc does exactly that, by
        // name, in the paragraph that justifies this very guard).
        val forbiddenLiterals = listOf(
            "\"tie_kept_local_ids\"",
            "\"stale_skipped_ids\"",
            "\"updated_issues\"",
        )
        val files = sourceFiles()

        val offenders = files.flatMap { file ->
            val text = file.readText()
            forbiddenLiterals.filter { text.contains(it) }.map { "${file.name}: $it" }
        }

        withClue("writeback main sources reference untrusted import-report fields: $offenders") {
            offenders.shouldBeEmpty()
        }
    }
}
