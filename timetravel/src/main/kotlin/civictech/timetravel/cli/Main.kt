package civictech.timetravel.cli

import civictech.timetravel.diff.JournalPresence
import civictech.timetravel.diff.RunDiff
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalReading
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.journal.JournalUnreadable
import civictech.timetravel.reconstruct.GraphSource
import civictech.timetravel.reconstruct.Reconstructor
import civictech.timetravel.reconstruct.ReconstructorResult
import civictech.timetravel.timeline.RunTimeline
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

/** The process entry point: [Main.run]'s return value is the exit code (computenet-3qkx1 D4). */
fun main(args: Array<String>): Unit = exitProcess(Main.run(args, System.out, System.err))

/**
 * The `timetravel` CLI (TTD1 F7, computenet-3qkx1 D1..D10; `[TTD1-47]`..`[TTD1-50]`): `inspect`,
 * `reconstruct` and `diff` over a journal file or directory, text by default and one DTO through
 * `CANONICAL_JSON` under `--json`.
 *
 * The exit code is the contract (`[TTD1-49]`): [OK] — it ran (and for `diff`, found no
 * divergence); [DIVERGED] — `diff` ran and found one; [FAILED] — it could not read or
 * reconstruct, or the command line is malformed. Honesty lives in the report, not the exit code:
 * `inspect` of a torn journal and a `Reconstruction` whose run is `Degraded` or `Unreconstructible`
 * are both [OK] (D10). Every expected refusal prints one line — the path or class, then the
 * reason — to stderr and no stack trace (`[TTD1-50]`); only a genuinely unexpected exception (a
 * `GraphSource` that throws while building, say) propagates.
 */
object Main {

    const val OK: Int = 0
    const val DIVERGED: Int = 1
    const val FAILED: Int = 2

    /** An expected refusal: [message] is the one stderr line (subject, then reason). */
    private class Refusal(override val message: String) : Exception(message)

    fun run(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        val command = try {
            Args.parse(args)
        } catch (e: UsageError) {
            err.println("timetravel: ${e.message}")
            err.println(Args.USAGE)
            return FAILED
        }
        return try {
            when (command) {
                is Command.Inspect -> inspect(command, out)
                is Command.Reconstruct -> reconstruct(command, out)
                is Command.Diff -> diff(command, out)
            }
        } catch (e: JournalUnreadable) {
            err.println("${e.path}: ${e.reason}")
            FAILED
        } catch (e: Refusal) {
            err.println(e.message)
            FAILED
        }
    }

    private fun inspect(command: Command.Inspect, out: PrintStream): Int {
        val report = InspectReport.of(open(command.path))
        out.println(if (command.json) report.toJson() else report.toText())
        return OK
    }

    private fun reconstruct(command: Command.Reconstruct, out: PrintStream): Int {
        val reading = open(command.path)
        val summary = reading.journals.singleOrNull()
            ?: throw Refusal(
                "${command.path}: reconstruct needs exactly one journal; the directory holds ${reading.journals.size}",
            )
        summary.refusal?.let { throw Refusal("${summary.journalId}: $it") }
        val timeline = RunTimeline.of(reading).getValue(summary.journalId)
        val graph = graphSource(command.graph)
        val reconstructor = when (val result = Reconstructor.of(reading, timeline, graph, command.seed)) {
            is ReconstructorResult.Ready -> result.reconstructor
            is ReconstructorResult.Refusal -> throw Refusal("${command.path}: ${result.reason}: ${result.message}")
        }
        // Resolve first, so an out-of-range --at is a refusal, not an exception out of stateAt.
        try {
            timeline.resolve(command.at)
        } catch (e: IndexOutOfBoundsException) {
            throw Refusal("--at: ${e.message}")
        }
        val report = ReconstructReport.of(reconstructor.stateAt(command.at), File(summary.journalId).name)
        out.println(if (command.json) report.toJson() else report.toText())
        return OK
    }

    /** computenet-3qkx1 D9: two files → `RunDiff.diff`, two directories → `RunDiff.diffReadings`. */
    private fun diff(command: Command.Diff, out: PrintStream): Int {
        val dirA = File(command.pathA).isDirectory
        val dirB = File(command.pathB).isDirectory
        if (dirA != dirB) {
            val (file, dir) = if (dirA) command.pathB to command.pathA else command.pathA to command.pathB
            throw Refusal("$file, $dir: cannot diff a journal file against a directory")
        }
        val readingA = open(command.pathA)
        val readingB = open(command.pathB)
        val graphA = graphSource(command.graphA)
        val graphB = graphSource(command.graphB)
        if (dirA) {
            if (command.seed != null) {
                throw Refusal("--seed: not supported for a directory diff (RunDiff.diffReadings reconstructs at seed 0)")
            }
            val report = RunDiff.diffReadings(readingA, readingB, graphA, graphB)
            out.println(if (command.json) report.toJson() else report.toText())
            val diverged = report.journals.any { it.presence != JournalPresence.BOTH || it.report?.divergence != null }
            return if (diverged) DIVERGED else OK
        }
        val seed = command.seed ?: 0L
        val tA = RunTimeline.of(readingA).values.single()
        val tB = RunTimeline.of(readingB).values.single()
        val report = RunDiff.diff(tA, tB, reconstructor(readingA, tA, graphA, seed), reconstructor(readingB, tB, graphB, seed))
        out.println(if (command.json) report.toJson() else report.toText())
        return if (report.divergence != null) DIVERGED else OK
    }

    /** A directory path reads as a [JournalSource.Directory]; anything else as a file (which refuses a non-file). */
    private fun open(path: String): JournalReading {
        val file = File(path)
        return JournalReader.open(if (file.isDirectory) JournalSource.Directory(file) else JournalSource.File(file))
    }

    private fun graphSource(flags: GraphFlags): GraphSource? = when (val load = GraphSourceLoader.load(flags)) {
        is GraphSourceLoad.Loaded -> load.source
        is GraphSourceLoad.Refused -> throw Refusal("${load.subject}: ${load.reason}")
    }

    /** `null` on a `NO_GRAPH_SOURCE` refusal: the diff reports that side's state as unavailable (si0tl-D10). */
    private fun reconstructor(reading: JournalReading, timeline: RunTimeline, graph: GraphSource?, seed: Long): Reconstructor? =
        (Reconstructor.of(reading, timeline, graph, seed) as? ReconstructorResult.Ready)?.reconstructor
}
