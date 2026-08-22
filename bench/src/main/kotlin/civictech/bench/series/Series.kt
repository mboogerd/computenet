package civictech.bench.series

import civictech.bench.RunEnvironment
import java.io.File

/**
 * Thrown when a series file, or a row of one, cannot be read or written honestly
 * (`computenet-b7k4`).
 */
class SeriesFormatException(message: String) : IllegalArgumentException(message)

/**
 * Whether the host was attested QUIESCED when a series entry was measured
 * (`computenet-b7k4`).
 *
 * This is an **attestation by the person or scheduler that ran the sweep**, not a
 * measurement — nothing in this module can prove a host was idle after the fact. It is
 * recorded per entry because it decides whether the entry may contribute to a tolerance
 * band: see [HistoricalBand.of], which forms bands from [QUIESCED] entries only.
 *
 * The reason it is a column rather than a precondition is the failure mode this whole
 * ticket exists to avoid. A first entry measured under interference is not a slightly
 * worse baseline; it is a **poisoned** one, because every later run is judged against the
 * band it seeds, and a band centred on a degraded number silently reclassifies healthy
 * runs as movement (and vice versa). Refusing to record such a run at all would push
 * people to record it as quiesced; recording it, marked, keeps the observation while
 * keeping it out of the band.
 */
enum class HostState {
    /**
     * The host was attested idle: no other interactive session, no build, no scheduled
     * scan. Entries in this state form bands.
     */
    QUIESCED,

    /**
     * The host was known to be doing something else — another agent session, a CI job, a
     * background scan. The entry is retained as an observation and is **excluded from
     * band formation**; it is still compared against the band like any other run.
     */
    SHARED,
}

/**
 * One benchmark's result from one run of the regression-tracking series
 * (`computenet-b7k4`).
 *
 * A series entry is deliberately **flatter and wider** than a
 * [civictech.bench.BenchResult]: it repeats the whole [RunEnvironment] on every row
 * rather than sharing one per table. That is redundancy on purpose. The series file is
 * append-only across months and machines, and the question a reader asks of it — "is this
 * run comparable to that one?" — has to be answerable from the two rows alone, without
 * reconstructing which run block each belonged to. [environmentFingerprint] is the
 * machine-checkable form of that question.
 *
 * @param runId the identifier of the run this row came from — the directory name under
 *   `bench/series/runs/` that holds the raw JMH CSV and log this row was ingested from.
 * @param runTimestampUtc when the run started, ISO-8601 UTC (`2026-08-22T07:04:00Z`).
 * @param benchmark the fully qualified `pkg.Class.method` name JMH reported.
 * @param params the `@Param` columns of the row, keyed by name. Part of the row's
 *   identity: `degree=16` and `degree=64` are different measurements of one benchmark.
 * @param mode JMH's mode column (`thrpt`, `avgt`, `ss`), carried through untouched.
 * @param value the score JMH reported.
 * @param dispersion JMH's reported error at 99.9% confidence, in [unit].
 * @param unit the unit both [value] and [dispersion] are expressed in.
 * @param hostState the quiescence attestation for the run — see [HostState].
 * @param env the environment the run was measured under, read off the run's own JMH log.
 */
data class SeriesEntry(
    val runId: String,
    val runTimestampUtc: String,
    val benchmark: String,
    val params: Map<String, String>,
    val mode: String,
    val value: Double,
    val dispersion: Double,
    val unit: String,
    val hostState: HostState,
    val env: RunEnvironment,
) {
    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(runTimestampUtc.isNotBlank()) { "runTimestampUtc must not be blank" }
        require(benchmark.isNotBlank()) { "benchmark must not be blank" }
        require(mode.isNotBlank()) { "mode must not be blank" }
        require(unit.isNotBlank()) { "unit must not be blank" }
        require(value.isFinite()) { "value must be finite, was $value" }
        require(dispersion.isFinite() && dispersion >= 0.0) {
            "dispersion must be finite and non-negative, was $dispersion"
        }
        require(params.keys.none { it.isBlank() }) { "param names must not be blank" }
        require(params.keys.none { it.contains(PARAM_PAIR_SEPARATOR) || it.contains(PARAM_KV_SEPARATOR) }) {
            "param names must not contain '$PARAM_PAIR_SEPARATOR' or '$PARAM_KV_SEPARATOR'"
        }
        require(params.values.none { it.contains(PARAM_PAIR_SEPARATOR) || it.contains(PARAM_KV_SEPARATOR) }) {
            "param values must not contain '$PARAM_PAIR_SEPARATOR' or '$PARAM_KV_SEPARATOR'"
        }
    }

    /**
     * What identifies this row's *measurement* across runs — the benchmark and its
     * parameters, and nothing about when or where it ran.
     *
     * Two entries with equal keys are two measurements of the same quantity; that is what
     * makes a band possible at all.
     */
    val key: SeriesKey get() = SeriesKey(benchmark, params)

    /**
     * The subset of [env] that has to MATCH for two entries to be comparable.
     *
     * Deliberately not the whole [RunEnvironment]. [RunEnvironment.harnessCommitSha]
     * changes on every commit and is the thing a series exists to vary; including it
     * would make every run its own incomparable population and no band would ever form.
     * Everything else in the environment is pinned on purpose — the machine, the JDK, the
     * heap, and JMH's own knobs — because each of them moves scores by more than the
     * effects this series is meant to detect. The two supersessions this ticket was filed
     * from were exactly a JDK-vendor substitution and an M2 Pro/M3 Max comparison; both
     * are differences this fingerprint refuses to average over.
     */
    val environmentFingerprint: EnvironmentFingerprint get() = EnvironmentFingerprint(env)

    companion object {
        /** Separates one `name=value` param pair from the next inside the CSV cell. */
        const val PARAM_PAIR_SEPARATOR: String = ";"

        /** Separates a param's name from its value inside the CSV cell. */
        const val PARAM_KV_SEPARATOR: String = "="
    }
}

/** The identity of a measured quantity across runs: benchmark plus its `@Param` values. */
data class SeriesKey(val benchmark: String, val params: Map<String, String>) {

    /** A stable, human-readable rendering — `Class.method[degree=16, elements=1000]`. */
    fun describe(): String {
        val shortName = benchmark.substringBeforeLast('.').substringAfterLast('.') +
            "." + benchmark.substringAfterLast('.')
        if (params.isEmpty()) return shortName
        val rendered = params.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }
        return "$shortName[$rendered]"
    }
}

/**
 * The pinned facts two runs must agree on before their numbers may be compared
 * (`computenet-b7k4`).
 *
 * Everything a [RunEnvironment] records EXCEPT [RunEnvironment.harnessCommitSha] — see
 * [SeriesEntry.environmentFingerprint] for why that one is excluded and the rest are not.
 */
data class EnvironmentFingerprint(
    val jvmVendor: String,
    val jvmVersion: String,
    val heapSettings: String,
    val cpuModel: String,
    val coreCount: Int,
    val os: String,
    val jmhMode: String,
    val forkCount: Int,
    val warmupIterations: Int,
    val measurementIterations: Int,
) {
    constructor(env: RunEnvironment) : this(
        jvmVendor = env.jvmVendor,
        jvmVersion = env.jvmVersion,
        heapSettings = env.heapSettings,
        cpuModel = env.cpuModel,
        coreCount = env.coreCount,
        os = env.os,
        jmhMode = env.jmhMode,
        forkCount = env.forkCount,
        warmupIterations = env.warmupIterations,
        measurementIterations = env.measurementIterations,
    )

    /** A one-line rendering, for a report that has to say why two runs were not compared. */
    fun describe(): String =
        "$cpuModel/$coreCount cores, $os, $jvmVendor $jvmVersion, heap $heapSettings, " +
            "JMH $jmhMode f=$forkCount wi=$warmupIterations i=$measurementIterations"
}

/**
 * The append-only CSV codec for a series file (`computenet-b7k4`).
 *
 * ## Why CSV in the repository, and not JSON, a database, or a CI artifact
 *
 * The storage decision this ticket left open is recorded in `doc/bench/regression-series.md`;
 * the part of it this object implements is the format. CSV because a series file is a
 * table whose columns never vary, because `git diff` on an appended row is one readable
 * line, and because JMH's own results files are CSV — a reader who can read one can read
 * the other. The **column set is fixed and verified on read**: a file whose header does
 * not match [HEADER] exactly is refused rather than positionally guessed at, because a
 * silently shifted column would move a score into a dispersion and produce a band that
 * looks fine.
 *
 * Values are written with [Double.toString], which round-trips exactly for a `Double`.
 * There is no formatting, rounding, or significant-figure policy here on purpose: this is
 * the machine-readable record, and rounding belongs to whatever renders it for a human.
 */
object SeriesCsv {

    /** The exact, ordered column set of a series file. */
    val HEADER: List<String> = listOf(
        "runId",
        "runTimestampUtc",
        "benchmark",
        "params",
        "mode",
        "score",
        "scoreError999",
        "unit",
        "hostState",
        "jvmVendor",
        "jvmVersion",
        "heapSettings",
        "cpuModel",
        "coreCount",
        "os",
        "jmhMode",
        "forkCount",
        "warmupIterations",
        "measurementIterations",
        "harnessCommitSha",
    )

    /** The header line an empty series file consists of. */
    fun headerLine(): String = HEADER.joinToString(",")

    /** Renders one entry as a CSV line, in [HEADER] order. */
    fun render(entry: SeriesEntry): String = listOf(
        entry.runId,
        entry.runTimestampUtc,
        entry.benchmark,
        encodeParams(entry.params),
        entry.mode,
        entry.value.toString(),
        entry.dispersion.toString(),
        entry.unit,
        entry.hostState.name,
        entry.env.jvmVendor,
        entry.env.jvmVersion,
        entry.env.heapSettings,
        entry.env.cpuModel,
        entry.env.coreCount.toString(),
        entry.env.os,
        entry.env.jmhMode,
        entry.env.forkCount.toString(),
        entry.env.warmupIterations.toString(),
        entry.env.measurementIterations.toString(),
        entry.env.harnessCommitSha,
    ).joinToString(",") { quote(it) }

    /**
     * Parses a whole series file.
     *
     * @param csv the file's full text. A file consisting of nothing but [headerLine]
     *   parses to an empty list — that is the legitimate state of a series that has been
     *   set up but not yet seeded, and it is NOT an error.
     * @param source where [csv] came from, named in every refusal so a reader learns
     *   which file to go look at.
     * @throws SeriesFormatException if the header does not match [HEADER], or a row has
     *   the wrong arity or an unparseable numeric or enum field.
     */
    fun parse(csv: String, source: String): List<SeriesEntry> {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) {
            throw SeriesFormatException(
                "$source is empty: a series file must carry its header line even when it " +
                    "holds no entries. Write '${headerLine()}' into it, or delete it and " +
                    "let scripts/bench-series/run-series.sh recreate it."
            )
        }
        val header = splitLine(lines.first())
        if (header != HEADER) {
            throw SeriesFormatException(
                "$source has an unexpected header. Expected ${HEADER.size} columns " +
                    "($HEADER) but found ${header.size} ($header). The column set is " +
                    "fixed and checked rather than guessed at positionally, because a " +
                    "shifted column would silently move a score into a dispersion."
            )
        }
        return lines.drop(1).mapIndexed { index, line -> parseRow(line, index + 2, source) }
    }

    /**
     * Appends [entries] to [file], creating it with a header if it does not exist.
     *
     * Append-only, for the same reason `doc/bench/findings.md` is: a series whose past
     * entries can be revised is one in which an inconvenient run can quietly stop
     * existing, and then the band means nothing. This function never rewrites an existing
     * line — it only ever adds. Removing an entry is a deliberate, reviewed edit to the
     * file by a human, recorded in the pull request that does it.
     */
    fun append(file: File, entries: List<SeriesEntry>) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(headerLine() + "\n")
        } else {
            // Refuse to append to a file we cannot read back — a malformed header here
            // would otherwise produce a file that is half one format and half another.
            parse(file.readText(), file.path)
        }
        if (entries.isEmpty()) return
        file.appendText(entries.joinToString(separator = "\n", postfix = "\n") { render(it) })
    }

    private fun parseRow(line: String, lineNumber: Int, source: String): SeriesEntry {
        val cells = splitLine(line)
        if (cells.size != HEADER.size) {
            throw SeriesFormatException(
                "$source line $lineNumber has ${cells.size} columns, expected ${HEADER.size}"
            )
        }
        fun cell(name: String): String = cells[HEADER.indexOf(name)]
        fun double(name: String): Double = cell(name).toDoubleOrNull()
            ?: throw SeriesFormatException(
                "$source line $lineNumber: column '$name' is '${cell(name)}', not a number"
            )
        fun int(name: String): Int = cell(name).toIntOrNull()
            ?: throw SeriesFormatException(
                "$source line $lineNumber: column '$name' is '${cell(name)}', not an integer"
            )

        val hostState = runCatching { HostState.valueOf(cell("hostState")) }.getOrNull()
            ?: throw SeriesFormatException(
                "$source line $lineNumber: column 'hostState' is '${cell("hostState")}', " +
                    "not one of ${HostState.entries.map { it.name }}"
            )

        return try {
            SeriesEntry(
                runId = cell("runId"),
                runTimestampUtc = cell("runTimestampUtc"),
                benchmark = cell("benchmark"),
                params = decodeParams(cell("params")),
                mode = cell("mode"),
                value = double("score"),
                dispersion = double("scoreError999"),
                unit = cell("unit"),
                hostState = hostState,
                env = RunEnvironment(
                    jvmVendor = cell("jvmVendor"),
                    jvmVersion = cell("jvmVersion"),
                    heapSettings = cell("heapSettings"),
                    cpuModel = cell("cpuModel"),
                    coreCount = int("coreCount"),
                    os = cell("os"),
                    jmhMode = cell("jmhMode"),
                    forkCount = int("forkCount"),
                    warmupIterations = int("warmupIterations"),
                    measurementIterations = int("measurementIterations"),
                    harnessCommitSha = cell("harnessCommitSha"),
                ),
            )
        } catch (e: IllegalArgumentException) {
            // RunEnvironment and SeriesEntry both refuse blank/non-positive fields. Those
            // refusals are the point — re-thrown with the location so the message names a
            // line rather than only a field.
            throw SeriesFormatException("$source line $lineNumber: ${e.message}")
        }
    }

    /** `degree=16;elements=1000`, sorted by name so the encoding is canonical. */
    fun encodeParams(params: Map<String, String>): String =
        params.entries.sortedBy { it.key }
            .joinToString(SeriesEntry.PARAM_PAIR_SEPARATOR) {
                "${it.key}${SeriesEntry.PARAM_KV_SEPARATOR}${it.value}"
            }

    /** [encodeParams] read back. An empty cell is no params, not a malformed one. */
    fun decodeParams(encoded: String): Map<String, String> {
        if (encoded.isBlank()) return emptyMap()
        return encoded.split(SeriesEntry.PARAM_PAIR_SEPARATOR).associate { pair ->
            val name = pair.substringBefore(SeriesEntry.PARAM_KV_SEPARATOR)
            val value = pair.substringAfter(SeriesEntry.PARAM_KV_SEPARATOR, "")
            if (name.isBlank()) {
                throw SeriesFormatException("param cell '$encoded' carries a pair with no name")
            }
            name to value
        }
    }

    /**
     * Minimal RFC-4180 quoting: a cell containing a comma, a quote or a newline is
     * wrapped in quotes with inner quotes doubled. CPU model strings and OS names carry
     * commas in practice, so this is not theoretical.
     */
    private fun quote(cell: String): String =
        if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + cell.replace("\"", "\"\"") + "\""
        } else {
            cell
        }

    /** [quote] read back, honouring quoted cells that contain commas. */
    private fun splitLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    cells.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        cells.add(current.toString())
        return cells
    }
}
