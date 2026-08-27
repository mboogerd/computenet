package civictech.bench.series

import civictech.bench.HarnessJarStamp
import civictech.bench.HostFacts
import civictech.bench.JarPath
import civictech.bench.MeasuringJvm
import civictech.bench.RunEnvironment
import civictech.bench.RunKnobs
import civictech.bench.micro.ThroughputReport
import java.io.File
import kotlin.system.exitProcess

/**
 * Turns one JMH run's own artifacts into [SeriesEntry] rows (`computenet-b7k4`).
 *
 * ## Why this reads the LOG and not this process
 *
 * Every environment fact on a series entry comes from the run's JMH log, through
 * [MeasuringJvm.fromJmhLog], [RunKnobs.fromJmhLog] and [HostFacts.fromJmhLog] — the three
 * readers `computenet-hqid`, `computenet-x9e.8` and `computenet-yhbd` added for exactly
 * this reason. This tool is a *renderer*: it runs after the forks have exited, possibly
 * days later and possibly on another machine, so its own `java.vendor`, `Runtime
 * .availableProcessors()` and `sysctl` reads answer a question nobody asked. Two entries
 * in `doc/bench/findings.md` shipped with the renderer's JVM in place of the measuring
 * one, and both had to be corrected by later entries.
 *
 * For a series the stake is higher than for a one-shot entry, because the environment is
 * not decoration here — it is the [EnvironmentFingerprint] that decides which past runs a
 * fresh one is comparable to. A wrong environment does not merely mislabel a row; it puts
 * it in the wrong population, and the band it then joins or is judged against is a band
 * over runs that were never comparable. So the refusals are load-bearing: a run whose log
 * is missing or bannerless produces **no** series entries, rather than entries carrying
 * the ingesting machine's facts.
 *
 * That is why `SmokeBenchmark` gained an `announceHost()` `@Setup(Level.Trial)` hook in
 * this same change: without it the sentinel's log carries no host banner, and the
 * benchmark whose drift `NOISE_FLOOR` was derived to detect could not enter the series at
 * all.
 */
object SeriesIngest {

    /**
     * Reads [results] and its adjacent log into series entries.
     *
     * @param results a JMH `-rf csv` results file.
     * @param runId the run's identifier — by convention the directory name under
     *   `bench/series/runs/` holding [results] and its log.
     * @param runTimestampUtc when the run started, ISO-8601 UTC.
     * @param hostState the quiescence attestation for the run. Stated by the caller
     *   because nothing here can measure it after the fact; see [HostState].
     * @param harnessCommitSha the harness commit that produced the run. No JMH artifact
     *   records it, so it stays the caller's to state.
     * @param log the run's log, defaulting to [ThroughputReport.runLogFor]`(results)` —
     *   the same `<results-basename>.log` convention every other renderer in this module
     *   uses.
     * @throws civictech.bench.MeasuringJvmUnknownException if the measuring JVM cannot be
     *   established from the log.
     * @throws civictech.bench.RunKnobsUnknownException if the JMH knobs cannot be.
     * @throws civictech.bench.HostFactsUnknownException if the host facts cannot be.
     * @throws civictech.bench.micro.ThroughputReportException if [results] is not a
     *   readable file or its columns cannot honestly become rows.
     */
    fun entriesFrom(
        results: File,
        runId: String,
        runTimestampUtc: String,
        hostState: HostState,
        harnessCommitSha: String,
        log: File = ThroughputReport.runLogFor(results),
    ): List<SeriesEntry> {
        require(results.isFile) { "JMH results file not found: ${results.absolutePath}" }
        require(log.isFile) {
            "JMH run log not found beside the results file: ${log.absolutePath}. The log " +
                "is the only record of which JVM, which knobs and which host produced " +
                "these numbers, and a series entry without them cannot be placed in a " +
                "comparable population. Re-run teeing stdout beside the results file."
        }
        val logText = log.readText()
        val source = log.path
        val env = RunEnvironment.forRun(
            measuringJvm = MeasuringJvm.fromJmhLog(logText, source),
            knobs = RunKnobs.fromJmhLog(logText, source),
            hostFacts = HostFacts.fromJmhLog(logText, source),
            harnessCommitSha = harnessCommitSha,
        )
        return ThroughputReport.parseCsv(results.readText()).map { row ->
            SeriesEntry(
                runId = runId,
                runTimestampUtc = runTimestampUtc,
                benchmark = row.benchmark,
                params = row.params,
                mode = row.mode,
                value = row.score,
                dispersion = row.scoreError,
                unit = row.unit,
                hostState = hostState,
                env = env,
            )
        }
    }
}

/**
 * The command-line face of the regression-tracking series (`computenet-b7k4`).
 *
 * Invoked through the `:bench:benchSeries` Gradle task, which is deliberately NOT
 * reachable from `check`, `build`, `test`, or any required CI check — see
 * `bench/build.gradle.kts` and `doc/bench/regression-series.md`. Two subcommands:
 *
 * ```
 * compare --results <csv> --series <csv> --run-id <id> --timestamp <iso8601>
 *         --host-state quiesced|shared --harness-sha <sha> --jar <bench-jmh.jar>
 *         [--log <log>]
 * append  <same arguments>
 * ```
 *
 * `compare` prints the report and changes nothing. `append` prints the same report and
 * then appends the run's rows to the series file. They are separate so the comparison
 * against history is always computed against the state BEFORE the run is folded in — a
 * single `record` command that appended first would have every run sitting inside its own
 * band.
 *
 * ## `--jar` is checked against `--harness-sha`, and its OWN stamp wins the recorded value (`computenet-0ado`)
 *
 * `--harness-sha` is the working-tree HEAD the caller (`run-series.sh`) reads immediately
 * before building `--jar` — not necessarily what `--jar` actually measures. Mirroring
 * `FloorTool.kt`'s `runIngest` (`computenet-7doz`), this reads `--jar`'s own stamped build
 * provenance via [HarnessJarStamp] and refuses when it disagrees with `--harness-sha` (the
 * stale-jar case: the jar was built from a different checkout than the one now measured —
 * e.g. `:bench:jmhJar` reported UP-TO-DATE and kept its old stamp after the checkout moved)
 * or is absent entirely (a jar that predates the stamp, or was not produced by
 * `:bench:jmhJar`). Only once the two agree is the STAMPED sha — not the working-tree one
 * — recorded on the resulting series entries, so a series row always names the commit the
 * jar was actually built from, never merely the commit the caller happened to have checked
 * out at the time. `--jar` is REQUIRED, not defaulted or optional: an optional flag would
 * be the unattested path left reachable by omitting it, which is exactly what this refusal
 * exists to close (mirroring `runIngest`, which requires `--jar` for the same reason).
 *
 * Exit codes: `0` on success, `1` on a refusal (a missing artifact, a bannerless log, a
 * malformed series file, or a stale or unstamped jar). **Movement is not an error.** A
 * scheduled run that finds a benchmark outside its band has done its job and says so in
 * its report; making that a non-zero exit would turn the lane into a gate, and this
 * ticket's scope explicitly keeps benchmark execution out of anything that gates.
 */
object SeriesCli {

    /** The parsed form of the shared argument set both subcommands take. */
    private data class Args(
        val results: File,
        val series: File,
        val runId: String,
        val timestamp: String,
        val hostState: HostState,
        val harnessSha: String,
        val jar: File,
        val log: File?,
    )

    private const val USAGE = """usage:
  benchSeries compare --results <jmh.csv> --series <series.csv> --run-id <id>
                      --timestamp <iso8601-utc> --host-state quiesced|shared
                      --harness-sha <sha> --jar <bench-jmh.jar> [--log <run.log>]
  benchSeries append  <the same arguments>

compare prints the report and writes nothing; append prints it and then appends the
run's rows. Movement beyond a band is reported, not signalled by the exit code.

--jar is checked against --harness-sha (the working-tree HEAD read before --jar was
built): a disagreement with --jar's own stamped build provenance (Harness-Commit-Sha,
written by :bench:jmhJar) is the stale-jar case and is refused, as is a --jar with no
such stamp. The stamped sha, not --harness-sha, is then what gets recorded
(computenet-0ado)."""

    /**
     * Runs the tool over [argv], writing to [out], and returns the process exit code.
     *
     * Separated from [main] so tests can drive it without exiting the test JVM.
     */
    fun run(argv: Array<String>, out: Appendable, cwd: File = File("").absoluteFile): Int {
        val command = argv.firstOrNull()
        if (command == null || command == "--help" || command == "-h") {
            out.appendLine(USAGE)
            return if (command == null) 1 else 0
        }
        if (command != "compare" && command != "append") {
            out.appendLine("unknown subcommand '$command'")
            out.appendLine(USAGE)
            return 1
        }
        val args = try {
            parse(argv.drop(1), cwd)
        } catch (e: IllegalArgumentException) {
            out.appendLine("bad arguments: ${e.message}")
            out.appendLine(USAGE)
            return 1
        }

        return try {
            require(args.jar.isFile) { "jar not found: ${args.jar.absolutePath}" }

            // The jar's own build provenance (`computenet-7doz`) — written by
            // `:bench:jmhJar` itself, not supplied by any caller. Absent entirely (no
            // manifest, or a manifest predating this stamp): refused, naming the rebuild
            // command, so the unattested path can never be reached silently. Mirrors
            // FloorTool.kt's `runIngest` exactly (`computenet-0ado`).
            val stampedSha = HarnessJarStamp.read(args.jar)
                ?: throw IllegalArgumentException(
                    "${args.jar.absolutePath} carries no " +
                        "'${HarnessJarStamp.MANIFEST_ATTRIBUTE}' manifest attribute — " +
                        "either it predates this stamp or was not produced by " +
                        "':bench:jmhJar'. A jar with no attested build provenance cannot " +
                        "be recorded as measuring anything. Rebuild it: " +
                        "./gradlew :bench:jmhJar"
                )

            // The stale-jar case (`computenet-7doz`): the jar on disk was built from a
            // different checkout than the one `run-series.sh` actually measured against.
            // --harness-sha is the working-tree HEAD read immediately before the jar was
            // built; a disagreement means the jar was not rebuilt after the checkout
            // changed, so it measures code from a commit other than the one now checked
            // out. Mirrors the mixed-harness-sha refusal's specificity (`computenet-0ado`).
            if (stampedSha != args.harnessSha) {
                throw IllegalArgumentException(
                    "run '${args.runId}' measured against ${args.jar.absolutePath}, built " +
                        "at '$stampedSha', but the working tree HEAD read immediately " +
                        "before that build was '${args.harnessSha}'. This is the stale-jar " +
                        "case: the jar was not rebuilt after the checkout changed, so it " +
                        "measures code from a commit other than the one now checked out. " +
                        "Rebuild the jar at this checkout (./gradlew :bench:jmhJar) and " +
                        "re-run this unit, or check out '$stampedSha' to match what the " +
                        "jar actually measures."
                )
            }
            // The recorded provenance, in preference to the working-tree sha above: the
            // jar's own stamp describes what was actually measured, and by this point the
            // two are known to agree (the mismatch above already refused otherwise) —
            // spelled out explicitly rather than reusing args.harnessSha, so a future
            // change to either branch above cannot silently start recording the wrong one.
            val harnessSha = stampedSha

            val fresh = SeriesIngest.entriesFrom(
                results = args.results,
                runId = args.runId,
                runTimestampUtc = args.timestamp,
                hostState = args.hostState,
                harnessCommitSha = harnessSha,
                log = args.log ?: ThroughputReport.runLogFor(args.results),
            )
            val history = if (args.series.isFile) {
                SeriesCsv.parse(args.series.readText(), args.series.path)
            } else {
                emptyList()
            }
            out.appendLine(SeriesComparator.report(SeriesComparator.compareAll(fresh, history)))
            if (command == "append") {
                SeriesCsv.append(args.series, fresh)
                out.appendLine()
                out.appendLine(
                    "Appended ${fresh.size} row(s) for run '${args.runId}' " +
                        "(${args.hostState}) to ${args.series.path}."
                )
                if (args.hostState == HostState.SHARED) {
                    out.appendLine(
                        "NOTE: host attested SHARED, so these rows are retained as " +
                            "observations and will NOT contribute to any tolerance band."
                    )
                }
            }
            0
        } catch (e: Exception) {
            out.appendLine("REFUSED: ${e.message}")
            1
        }
    }

    private fun parse(argv: List<String>, cwd: File): Args {
        val map = mutableMapOf<String, String>()
        var i = 0
        while (i < argv.size) {
            val flag = argv[i]
            require(flag.startsWith("--")) { "expected a --flag, found '$flag'" }
            require(i + 1 < argv.size) { "flag '$flag' has no value" }
            map[flag.removePrefix("--")] = argv[i + 1]
            i += 2
        }
        fun required(name: String): String = map[name]
            ?: throw IllegalArgumentException("missing required --$name")

        val hostStateText = required("host-state")
        val hostState = HostState.entries.firstOrNull { it.name.equals(hostStateText, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "--host-state must be one of ${HostState.entries.map { it.name.lowercase() }}, " +
                    "was '$hostStateText'"
            )

        return Args(
            results = File(required("results")),
            series = File(required("series")),
            runId = required("run-id"),
            timestamp = required("timestamp"),
            hostState = hostState,
            harnessSha = required("harness-sha"),
            jar = JarPath.resolve(required("jar"), cwd),
            log = map["log"]?.let { File(it) },
        )
    }
}

/** Process entry point for the `:bench:benchSeries` task. */
fun main(argv: Array<String>) {
    val out = StringBuilder()
    val code = SeriesCli.run(argv, out)
    print(out)
    exitProcess(code)
}
