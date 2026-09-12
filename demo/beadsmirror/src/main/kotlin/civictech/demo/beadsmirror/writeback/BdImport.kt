package civictech.demo.beadsmirror.writeback

import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * One completed `bd import` invocation, exactly as the process left it.
 *
 * [stdout] is carried verbatim and **is never parsed by anything in this
 * package** — see [BdImport]'s KDoc for why. It exists so a failure record can
 * quote bd's own words ([WriteBackFailure.ImportExited]); on success nothing
 * reads it at all.
 */
data class ImportResult(val exitCode: Int, val stdout: String, val stderr: String) {

    /** Whether `bd` exited zero. NOT whether the row landed — that is decided by a re-read. */
    val succeeded: Boolean get() = exitCode == 0
}

/**
 * The write-back applier's import runner: ONE `bd import --allow-stale`
 * invocation per row (feature computenet-6wc.1 clause 1, task
 * computenet-6wc.1.3).
 *
 * **The single-row invariant is structural, not a convention.** [importRow]
 * takes exactly one [JsonObject] and there is deliberately no list overload,
 * no `Iterable` overload, and no batching entry point — so "one invocation per
 * row" cannot be violated by calling this class differently, only by editing
 * it. Bulk `--allow-stale` is barred by measurement, not taste: the flag is
 * evaluated **per run**, so a bulk import under it clobbers bystander rows
 * that were never part of the imposition (epic computenet-6wc claim (b) E3,
 * `doc/spike/bds0/claim-b-ordering-authority.md` "Instrument 1"), and under
 * the flag bd's report additionally drops `stale_skipped_ids`/`updated`/
 * `updated_issues` altogether. The per-row Dolt commit cost that follows is
 * accepted at epic level.
 *
 * **Nothing reads fields out of the report.** `tie_kept_local_ids`,
 * `stale_skipped_ids`, `updated_issues` and `updated` are measurably
 * untrustworthy — an incoming sub-second `updated_at` of `>= .500` rounds up,
 * overwrites the local value, and is nonetheless reported as a *tie* (E4,
 * reproduced 2026-09-12 against bd 1.1.2 / dolt 2.2.3). So the outcome of an
 * import is decided by a post-import `bd export` RE-READ
 * ([WriteBackApplier.applyOnce]), and [ImportResult.stdout] survives only as
 * failure evidence.
 *
 * **Invocation.** `bd --sandbox import - --json --allow-stale`, working
 * directory [workspaceRoot], the one row written to stdin as a single JSONL
 * line plus a newline. `--sandbox` is the same guard
 * [civictech.demo.beadsmirror.baseline.BdExportReader] carries: without it
 * `bd` resolves upwards and a workspace whose database has gone missing would
 * silently import into *this repository's* live `.beads` instead of failing.
 * Both streams are drained on their own threads for the reason that reader
 * gives — a subprocess that fills one pipe's OS buffer while we block on the
 * other deadlocks against us.
 *
 * This is the one file in `civictech.demo.beadsmirror.writeback` permitted to
 * spawn a process; `WriteBackPurityTest` exempts it **by this exact basename**
 * and flags every other file in the package. Renaming it holes that guard
 * open.
 */
class BdImport(private val workspaceRoot: Path) {

    /**
     * Imports [row] — one issue, one invocation — and returns what `bd` did.
     *
     * Never throws on a non-zero exit: the refusal IS the result the applier
     * records as [WriteBackFailure.ImportExited], and replacing it with an
     * exception would lose the queue-continuation property clause 6 requires.
     */
    fun importRow(row: JsonObject): ImportResult {
        val process = ProcessBuilder("bd", "--sandbox", "import", "-", "--json", "--allow-stale")
            .directory(workspaceRoot.toFile())
            .start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = thread(name = "bd-import-stdout") {
            process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) }
        }
        val stderrReader = thread(name = "bd-import-stderr") {
            process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) }
        }

        process.outputStream.bufferedWriter().use { it.write(row.toString() + "\n") }

        val exitCode = process.waitFor()
        stdoutReader.join()
        stderrReader.join()

        return ImportResult(exitCode, stdout.toString(), stderr.toString())
    }
}
