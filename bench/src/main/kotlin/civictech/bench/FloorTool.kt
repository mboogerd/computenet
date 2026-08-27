package civictech.bench

import java.io.File
import java.net.URLClassLoader
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.jar.JarFile
import kotlin.system.exitProcess

/**
 * The command-line face of [FloorDerivationLedger] (`computenet-3omz`).
 *
 * Invoked through the `:bench:floorTool` Gradle task, which is deliberately NOT reachable
 * from `check`, `build`, `test`, or any required CI check — see `bench/build.gradle.kts`
 * and the same reasoning `:bench:benchSeries` documents there. **This tool owns no
 * policy.** Every refusal it can produce — an incomplete row set, a second measuring JVM,
 * a second harness sha, a fourth observation, a plan whose enumeration disagrees with the
 * pre-registered row count — is [FloorLedgerException] thrown by [FloorDerivationLedger] or its collaborator
 * types; this file catches it, prints its message to the caller, and exits non-zero. It
 * never re-implements, softens, or bypasses one.
 *
 * ## Enumeration route: `-lp` verified against the built jar (not `-l` plus reflection)
 *
 * The bead marked the `-lp` route `unverified:` and prescribed checking it before relying
 * on it. Verified 2026-08-26 against `bench/build/libs/bench-jmh.jar` (JMH 1.37):
 *
 * ```
 * $ java -jar bench/build/libs/bench-jmh.jar -lp 'civictech\.bench\.micro\.CellFootprintBenchmark'
 * Benchmarks:
 * civictech.bench.micro.CellFootprintBenchmark.realSnapshot
 *   param "scale" = {N1E3, N1E4, N1E5}
 *   param "family" = {SET_CELL, MAP_CELL, OR_MAP_CELL, KEYED_SET_CELL, LIST_CELL, COUNTER_CELL, PN_COUNTER_CELL}
 * ```
 *
 * checked the same way against `OperatorThroughputBenchmark` (two methods, two @Param
 * enums each), `FanOutScalingBenchmark` (six methods, one @Param enum), `BoundedReadBenchmark`
 * (two methods, one @Param enum) and `SmokeBenchmark` (one method, no @Param). The shape
 * is one benchmark-method line per method, followed by one `  param "<name>" = {v1, v2,
 * ...}` line per `@Param` field — exactly enough to reconstruct the method's full row
 * cross-product without running anything. [EnumerationRoute.enumerate] parses that shape
 * directly. The `-l`-plus-reflection fallback the bead names is not implemented: nothing
 * exercised it, and building an untested fallback path for a route that turned out to
 * work is dead code wearing a safety net's clothes. If `-lp`'s output shape ever changes
 * underneath a future JMH upgrade, [EnumerationRoute.enumerate] fails loudly (it cannot
 * parse a shape it does not recognise) rather than silently mis-enumerating, so that
 * failure is the signal to revisit this decision, not a corrupted plan.
 *
 * ## `next`'s unit sizing
 *
 * [UnitSizing] estimates one row's wall time from the class's own `@Fork`/`@Warmup`/
 * `@Measurement` configuration, read by reflection off the benchmark class loaded from the
 * same jar `plan` enumerated against — not hand-copied numbers, so a config change is
 * picked up automatically rather than silently going stale. The estimate is a scheduling
 * aid ONLY: [UnitSizing] never touches a JMH knob, and nothing it computes is a refusal —
 * the ledger's refusals are the only ones. See [UnitSizing]'s KDoc for the arithmetic and
 * for what happens when a single row alone cannot fit the window (it still runs; the
 * config is not shrunk to make it).
 */
object FloorTool

// -----------------------------------------------------------------------------------
// `--jar` resolution (`plan`, `next`) — computenet-x9e.15.
// -----------------------------------------------------------------------------------

/**
 * Resolves a `--jar` value the way an operator typing it at the repo root expects, not the
 * way `:bench:floorTool`'s `JavaExec` actually runs.
 *
 * `JavaExec`'s working directory defaults to the *project* directory (`bench/`), not the
 * directory `./gradlew` was invoked from. The tooling's own prose — this file's [FloorCli]
 * usage block, `doc/bench/regression-series.md`, `derive-class-floor.sh`'s echoed commands —
 * all print `--jar bench/build/libs/bench-jmh.jar`, which is repo-root-relative and exactly
 * what an operator standing at the repo root will copy. Resolved naively against the actual
 * working directory, that path becomes `bench/bench/build/libs/bench-jmh.jar` — a doubled
 * segment that does not exist, and whose refusal reads exactly like the refusal several BEN1
 * items deliberately trigger to test (computenet-x9e.15; nine such refusals were read as
 * findings before the doubled segment was noticed).
 *
 * [resolve] tries, in order: the path as given if absolute; the path against [cwd] (so a
 * caller that already runs with the repo root as its working directory — direct `java`
 * invocation, a future `workingDir` change — keeps working unchanged); then the path against
 * the repository root, found by walking up from [cwd] to the nearest ancestor containing
 * `settings.gradle.kts`. If none of those is a file, the thrown message names every absolute
 * path it tried, so the next path mistake is self-diagnosing rather than merely documented.
 */
object JarPath {

    fun resolve(raw: String, cwd: File = File("").absoluteFile): File {
        val direct = File(raw)
        if (direct.isAbsolute) return direct
        val cwdAttempt = File(cwd, raw)
        if (cwdAttempt.isFile) return cwdAttempt
        val repoRoot = findRepoRoot(cwd)
        val rootAttempt = repoRoot?.let { File(it, raw) }
        if (rootAttempt != null && rootAttempt.isFile) return rootAttempt
        val attempts = listOfNotNull(cwdAttempt, rootAttempt).joinToString(" and ") { it.path }
        throw IllegalArgumentException(
            "jar not found: tried $attempts (a relative --jar is resolved against the " +
                "working directory first, then against the repository root; pass an " +
                "absolute path to bypass both attempts)"
        )
    }

    /** Walks up from [start] to the nearest ancestor containing `settings.gradle.kts`. */
    private fun findRepoRoot(start: File): File? {
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }
}

// -----------------------------------------------------------------------------------
// The jar's own build provenance (`computenet-7doz`), read back at `ingest`.
// -----------------------------------------------------------------------------------

/**
 * Reads the commit sha `bench/build.gradle.kts`'s `jmhJar` task stamps into
 * `bench-jmh.jar`'s manifest at build time — the jar's own build provenance, as opposed to
 * [UnitAttestation.harnessSha]'s working-tree HEAD read at measurement time.
 *
 * The whole point of this type existing separately from a working-tree `git rev-parse` is
 * that the two can disagree: the jar is built once, outside the gate, and a stale jar
 * carried across a checkout change without rebuilding would otherwise be recorded under a
 * sha its code did not come from. `runIngest` refuses exactly that disagreement.
 */
object HarnessJarStamp {

    /**
     * Must match the literal `bench/build.gradle.kts`'s `jmhJar` task writes — a build
     * script cannot depend on this module's own compiled output, so the two sides are kept
     * equal by comment rather than by a shared constant.
     */
    const val MANIFEST_ATTRIBUTE = "Harness-Commit-Sha"

    /**
     * The stamped sha, or `null` if [jar] carries no manifest, or a manifest with no such
     * attribute — a jar that predates this stamp, or was produced by something other than
     * `:bench:jmhJar`.
     */
    fun read(jar: File): String? =
        JarFile(jar).use { it.manifest?.mainAttributes?.getValue(MANIFEST_ATTRIBUTE) }
}

// -----------------------------------------------------------------------------------
// Enumeration (`plan`).
// -----------------------------------------------------------------------------------

/**
 * Turns `java -jar <jar> -lp '<regex>'`'s output into a benchmark class's full row
 * universe, and keeps the verbatim output as [EnumerationResult.provenance].
 */
object EnumerationRoute {

    private val METHOD_LINE = Regex("""^(\S+)\.(\w+)$""")
    private val PARAM_LINE = Regex("""^\s+param\s+"([^"]+)"\s*=\s*\{(.*)}\s*$""")

    data class EnumerationResult(val rows: List<RowKey>, val provenance: String)

    /**
     * Runs `-lp` over [benchmarkClass] against [jar] using [javaExecutable] — the same JVM
     * this tool is itself running under, which the `:bench:floorTool` Gradle task pins to
     * the module's toolchain, so the enumeration and the eventual measurement share a
     * launcher unless the operator overrides it. Refuses (via [FloorLedgerException]) on a
     * non-zero exit, empty output, or output this parser does not recognise as `-lp`'s
     * documented shape.
     */
    fun enumerate(
        jar: File,
        benchmarkClass: String,
        javaExecutable: String = defaultJavaExecutable(),
    ): EnumerationResult {
        require(jar.isFile) { "jar not found: ${jar.absolutePath}" }
        val regex = "civictech\\.bench\\.micro\\.$benchmarkClass\\."
        val command = listOf(javaExecutable, "-jar", jar.path, "-lp", regex)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            throw FloorLedgerException(
                "enumeration command ${command.joinToString(" ")} exited $exit; output:\n$output"
            )
        }
        val rows = parse(output, benchmarkClass, command.joinToString(" "))
        val provenance = "${command.joinToString(" ")}\n$output"
        return EnumerationResult(rows, provenance)
    }

    /**
     * Parses `-lp`'s output text into [benchmarkClass]'s rows. Public rather than
     * private so [FloorToolTest] can pin the parsing against canned `-lp` output without
     * spawning a JVM per test; [enumerate] is the only caller in production.
     */
    fun parse(output: String, benchmarkClass: String, command: String): List<RowKey> {
        val rows = mutableListOf<RowKey>()
        var currentMethod: String? = null
        // Ordered so the cartesian product below is deterministic and matches the order
        // `-lp` printed the @Param fields in.
        val currentParams = LinkedHashMap<String, List<String>>()

        fun flush() {
            val method = currentMethod ?: return
            if (currentParams.isEmpty()) {
                rows += RowKey.of(method, emptyMap())
            } else {
                rows += cartesianProduct(currentParams).map { RowKey.of(method, it) }
            }
            currentParams.clear()
        }

        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (line.isBlank() || line == "Benchmarks:") return@forEach
            val paramMatch = PARAM_LINE.matchEntire(line)
            if (paramMatch != null) {
                if (currentMethod == null) {
                    throw FloorLedgerException(
                        "enumeration output has a param line before any benchmark line: " +
                            "'$line' (command: $command)"
                    )
                }
                val (name, valuesText) = paramMatch.destructured
                val values = valuesText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                currentParams[name] = values
                return@forEach
            }
            val methodMatch = METHOD_LINE.matchEntire(line)
            if (methodMatch != null) {
                flush()
                val (owner, method) = methodMatch.destructured
                val ownerClass = owner.substringAfterLast('.')
                if (ownerClass != benchmarkClass) {
                    throw FloorLedgerException(
                        "enumeration for '$benchmarkClass' returned a row for '$ownerClass' " +
                            "($line); the -lp filter matched more than the intended class " +
                            "(command: $command)"
                    )
                }
                currentMethod = method
                return@forEach
            }
            throw FloorLedgerException(
                "enumeration output has a line this parser does not recognise as -lp's " +
                    "documented shape ('<fqcn> ' or '  param \"name\" = {v1, v2}'): '$line' " +
                    "(command: $command). If JMH's -lp output shape changed, the fallback " +
                    "route (-l plus reading @Param fields) named in computenet-3omz.2's " +
                    "bead needs to be implemented; it was not needed when this was written."
            )
        }
        flush()
        if (rows.isEmpty()) {
            throw FloorLedgerException(
                "enumeration for '$benchmarkClass' produced no rows at all (command: " +
                    "$command); output:\n$output"
            )
        }
        return rows
    }

    private fun cartesianProduct(params: LinkedHashMap<String, List<String>>): List<Map<String, String>> {
        var acc: List<Map<String, String>> = listOf(emptyMap())
        params.forEach { (name, values) ->
            acc = acc.flatMap { partial -> values.map { value -> partial + (name to value) } }
        }
        return acc
    }

    private fun defaultJavaExecutable(): String =
        File(System.getProperty("java.home"), "bin/java").path
}

// -----------------------------------------------------------------------------------
// Unit sizing (`next`). Scheduling only — see FloorTool's KDoc, "next's unit sizing".
// -----------------------------------------------------------------------------------

/**
 * Estimates one row's measured wall time from a benchmark class's OWN `@Fork`/`@Warmup`/
 * `@Measurement` configuration, read by reflection so a config change is picked up rather
 * than silently going stale against a hand-copied number.
 *
 * The estimate is `forks x (warmupIterations x warmupSeconds + measurementIterations x
 * measurementSeconds) + forks x [FORK_STARTUP_OVERHEAD_SECONDS]`. The overhead term is a
 * deliberately rough constant, not a measured one — its only job is to leave headroom so
 * `next` does not schedule a unit that JVM startup and classloading alone push over the
 * window, and getting it somewhat wrong costs schedule accuracy, never correctness: the
 * ledger's refusals are unaffected by anything this object computes.
 *
 * `@Warmup`/`@Measurement` under `Mode.SingleShotTime` (`FanOutScalingBenchmark`'s
 * `*FixedState` methods) carry `time = -1` — JMH's own sentinel for "not applicable in
 * this mode", since a single-shot iteration's duration is whatever the one invocation
 * takes rather than a controlled duration. [SINGLE_SHOT_ITERATION_ESTIMATE_SECONDS]
 * stands in for that unmeasured per-iteration cost; it is exactly as rough as the fork
 * overhead and exists for the same reason.
 */
object UnitSizing {

    /** Rough per-fork JVM startup and classloading allowance. Not measured; see KDoc. */
    const val FORK_STARTUP_OVERHEAD_SECONDS: Double = 5.0

    /** Stand-in per-iteration cost under `SingleShotTime`, where `@Warmup`/`@Measurement`
     * report no time. Not measured; see KDoc. */
    const val SINGLE_SHOT_ITERATION_ESTIMATE_SECONDS: Double = 1.0

    /**
     * Target wall time for one `next`-emitted unit: comfortably under the 10-minute
     * ceiling `computenet-3omz`'s feature names, so one foreground call plus reporting
     * overhead still fits inside a short quiesced window.
     */
    const val TARGET_UNIT_SECONDS: Double = 480.0

    /** One row's estimated seconds, reading [method]'s own annotations (falling back to
     * the class's) off [benchmarkClass] as loaded from [jar]. */
    fun estimateRowSeconds(jar: File, benchmarkClass: String, method: String): Double {
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()))
        try {
            val cls = loader.loadClass("civictech.bench.micro.$benchmarkClass")
            val declaredMethod = cls.declaredMethods.firstOrNull { it.name == method }
                ?: throw FloorLedgerException(
                    "'$benchmarkClass' as loaded from ${jar.path} has no method named " +
                        "'$method'; the plan and the jar disagree about this class's shape"
                )
            val fork = declaredMethod.annotationValue("Fork") ?: cls.annotationValue("Fork")
                ?: throw FloorLedgerException(
                    "'$benchmarkClass.$method' carries no @Fork, at method or class level"
                )
            val warmup = declaredMethod.annotationValue("Warmup") ?: cls.annotationValue("Warmup")
                ?: throw FloorLedgerException(
                    "'$benchmarkClass.$method' carries no @Warmup, at method or class level"
                )
            val measurement = declaredMethod.annotationValue("Measurement")
                ?: cls.annotationValue("Measurement")
                ?: throw FloorLedgerException(
                    "'$benchmarkClass.$method' carries no @Measurement, at method or class level"
                )

            val forks = (fork.invoke("value") as Int)
            val warmupIterations = (warmup.invoke("iterations") as Int)
            val warmupSeconds = timeSeconds(warmup)
            val measurementIterations = (measurement.invoke("iterations") as Int)
            val measurementSeconds = timeSeconds(measurement)

            return forks * (
                warmupIterations * warmupSeconds + measurementIterations * measurementSeconds
                ) + forks * FORK_STARTUP_OVERHEAD_SECONDS
        } finally {
            loader.close()
        }
    }

    private fun timeSeconds(annotation: Any): Double {
        val time = (annotation.invoke("time") as Int)
        // -1 is JMH's own sentinel for "not applicable" under SingleShotTime — see KDoc.
        return if (time <= 0) SINGLE_SHOT_ITERATION_ESTIMATE_SECONDS else time.toDouble()
    }

    /** Reads annotation [simpleName]'s instance off this reflective element, or null. */
    private fun java.lang.reflect.AnnotatedElement.annotationValue(simpleName: String): Any? =
        annotations.firstOrNull { it.annotationClass.java.simpleName == simpleName }

    private fun Any.invoke(methodName: String): Any =
        this.javaClass.getMethod(methodName).invoke(this)
}

/**
 * Groups a ledger's outstanding rows into one unit sized to fit
 * [UnitSizing.TARGET_UNIT_SECONDS] — the `next` subcommand's arithmetic.
 *
 * **The unit of decomposition is the ROW SET, never the configuration.** A unit selects
 * a subset of a class's OWN rows; it never changes fork count, iteration count, iteration
 * duration, thread count or benchmark mode to make that subset fit — [UnitSizing] only
 * estimates how many rows fit, it computes nothing a JMH invocation flag could shrink.
 *
 * ## How a batch is chosen
 *
 * Every class this ledger derives for has at most two `@Param` dimensions (verified
 * against the enums `EXPECTED_PLAN_ROW_COUNTS` documents). A batch therefore picks one
 * method, fixes every other `@Param` to the value its FIRST still-outstanding row carries,
 * and varies exactly one `@Param` — the one with the most distinct outstanding values
 * among that method's rows, so a batch actually shrinks the biggest remaining dimension
 * first. That sub-cube is walked in plan order, accumulating rows while the running
 * estimate stays at or under [UnitSizing.TARGET_UNIT_SECONDS]; the first row is always
 * included even if its own estimate alone exceeds the target, because there is nothing
 * left to shrink to make it fit — the class's own configuration is the floor.
 */
object NextPlanner {

    /** One `next`-emitted unit: the rows it selects and how to invoke JMH for them. */
    data class NextUnit(
        val benchmarkClass: String,
        val method: String,
        val fixedParams: Map<String, String>,
        val varyingParam: String?,
        val varyingValues: List<String>,
        val rows: List<RowKey>,
        val estimatedSeconds: Double,
    ) {
        /** The benchmark regex naming exactly this method, anchored so it cannot also
         * match a method whose name this one is a prefix of (`sim` vs `simFixedState`). */
        val benchmarkRegex: String
            get() = "civictech\\.bench\\.micro\\.$benchmarkClass\\.$method$"

        /** The `-p` flags a runner script passes alongside [benchmarkRegex]. */
        fun paramFlags(): List<String> = buildList {
            fixedParams.toSortedMap().forEach { (name, value) -> add("-p $name=$value") }
            if (varyingParam != null) {
                add("-p $varyingParam=${varyingValues.joinToString(",")}")
            }
        }

        /** The invocation a runner script would run, as one printable line. */
        fun describeInvocation(): String =
            (listOf("'$benchmarkRegex'") + paramFlags()).joinToString(" ")
    }

    /**
     * The next unit to measure, or `null` if [ledger] is already complete.
     *
     * @param estimateRowSeconds one row's estimated wall time, given the benchmark class
     *   and method — [UnitSizing.estimateRowSeconds] in production, reading the class's
     *   own `@Fork`/`@Warmup`/`@Measurement` off a built jar by reflection. Taken as a
     *   function rather than a jar path so this batching decision — which rows go
     *   together, and that a single row too large to fit still runs alone — is testable
     *   against a canned estimate, with no jar, no `URLClassLoader`, and no JMH annotation
     *   dependency anywhere near `:bench:test`.
     */
    fun next(
        ledger: FloorDerivationLedger,
        estimateRowSeconds: (benchmarkClass: String, method: String) -> Double,
    ): NextUnit? {
        val outstanding = ledger.outstanding()
        if (outstanding.isEmpty()) return null

        val anchor = ledger.plan.rows.first { outstanding.containsKey(it) }
        val method = anchor.method
        val methodOutstanding = ledger.plan.rows.filter {
            it.method == method && outstanding.containsKey(it)
        }

        val paramKeys = anchor.params.keys
        val varyingParam: String? = paramKeys.maxByOrNull { key ->
            methodOutstanding.mapNotNull { it.params[key] }.distinct().size
        }
        val fixedParams = anchor.params.filterKeys { it != varyingParam }

        val subCube = methodOutstanding.filter { row ->
            fixedParams.all { (key, value) -> row.params[key] == value }
        }

        val rowSeconds = estimateRowSeconds(ledger.plan.benchmarkClass, method)

        val included = mutableListOf<RowKey>()
        var total = 0.0
        for (row in subCube) {
            if (included.isNotEmpty() && total + rowSeconds > UnitSizing.TARGET_UNIT_SECONDS) {
                break
            }
            included += row
            total += rowSeconds
        }

        val varyingValues = if (varyingParam == null) {
            emptyList()
        } else {
            included.mapNotNull { it.params[varyingParam] }
        }

        return NextUnit(
            benchmarkClass = ledger.plan.benchmarkClass,
            method = method,
            fixedParams = fixedParams,
            varyingParam = varyingParam,
            varyingValues = varyingValues,
            rows = included,
            estimatedSeconds = total,
        )
    }
}

// -----------------------------------------------------------------------------------
// Rendering (`render`) — the ClassNoiseFloor constructor call and the findings.md block.
// -----------------------------------------------------------------------------------

/** A Kotlin string literal for [value], escaping only what a Kotlin string needs. */
private fun kotlinStringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * The `ClassNoiseFloor(...)` constructor call to paste into
 * [CLASS_NOISE_FLOOR_DERIVATIONS], rendered from [record] itself so it cannot drift from
 * the number [record] actually carries.
 */
fun renderConstructorCall(record: ClassNoiseFloor): String = buildString {
    appendLine("ClassNoiseFloor(")
    appendLine("    benchmarkClass = ${kotlinStringLiteral(record.benchmarkClass)},")
    appendLine("    observedMaxRelativeDispersion = ${record.observedMaxRelativeDispersion},")
    appendLine("    runs = ${record.runs},")
    appendLine("    derivedOn = ${kotlinStringLiteral(record.derivedOn)},")
    appendLine("    harnessCommitSha = ${kotlinStringLiteral(record.harnessCommitSha)},")
    appendLine("    hostState = QUIESCED_HOST_STATE,")
    appendLine("    jmhConfig = ${kotlinStringLiteral(record.jmhConfig)},")
    appendLine("    measuringJvm = ${kotlinStringLiteral(record.measuringJvm)},")
    // Emitted so the pasted entry carries its own gathering provenance. Without it a
    // unit-assembled derivation would be committed with the field absent, and a later
    // `render --existing` — which has no ledger to consult — could say nothing about how
    // the observations were taken (`computenet-71hu`).
    when (val assembly = record.assembly) {
        is DerivationAssembly.WholeClassRuns ->
            appendLine("    assembly = DerivationAssembly.WholeClassRuns(runs = ${assembly.runs}),")
        is DerivationAssembly.UnitAssembled ->
            appendLine("    assembly = DerivationAssembly.UnitAssembled(units = ${assembly.units}),")
        null -> {}
    }
    append(")")
}

// -----------------------------------------------------------------------------------
// The command-line face.
// -----------------------------------------------------------------------------------

/**
 * Tokenises one raw command line into arguments, honouring single quotes, double quotes
 * and backslash escapes.
 *
 * This exists because Gradle can hand a `JavaExec` task exactly ONE string
 * (`-PfloorArgs=...`), and `bench/build.gradle.kts` used to split that string on
 * whitespace before the tool saw it. Any value containing a space was therefore torn in
 * half: `--jmh-config 'forks=1, warmup 3x1s'` — a config phrased the way `doc/bench/`
 * `findings.md`'s existing entries phrase that field, and the way this tool's own printed
 * template shows it — was refused with `expected a --flag, found 'warmup'`, so the
 * template could not be followed as printed (`computenet-71hu`). Tokenising HERE, from
 * the raw string, is what lets the quoting the operator already wrote survive.
 *
 * The rules are the shell's, restricted to what a command line needs: unquoted whitespace
 * separates arguments; `\` outside single quotes escapes the next character; `'...'` is
 * literal; `"..."` is literal except for `\`. An unterminated quote is refused rather
 * than closed at end of input — a value silently truncated at a missing quote is the
 * failure this whole function exists to stop being invisible.
 */
fun splitFloorArgs(raw: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var started = false
    var quote: Char? = null
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        when {
            quote == '\'' -> {
                if (c == '\'') quote = null else current.append(c)
                started = true
            }
            quote == '"' -> {
                if (c == '"') {
                    quote = null
                } else if (c == '\\' && i + 1 < raw.length) {
                    current.append(raw[i + 1]); i += 1
                } else {
                    current.append(c)
                }
                started = true
            }
            c == '\'' || c == '"' -> {
                quote = c; started = true
            }
            c == '\\' && i + 1 < raw.length -> {
                current.append(raw[i + 1]); i += 1; started = true
            }
            c.isWhitespace() -> {
                if (started) { tokens += current.toString(); current.clear(); started = false }
            }
            else -> {
                current.append(c); started = true
            }
        }
        i += 1
    }
    require(quote == null) {
        "unterminated ${if (quote == '\'') "single" else "double"} quote in floorArgs: " +
            "$raw. A value whose quote is never closed would otherwise be silently " +
            "truncated, which is the shape of defect this tokeniser exists to prevent"
    }
    if (started) tokens += current.toString()
    return tokens
}

/**
 * Parses `--flag value` pairs, mirroring `civictech.bench.series.SeriesCli`'s parser.
 */
private fun parseFlags(argv: List<String>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < argv.size) {
        val flag = argv[i]
        require(flag.startsWith("--")) {
            "expected a --flag, found '$flag'. If this is part of a value containing " +
                "spaces, quote it: -PfloorArgs=\"... --jmh-config 'forks=1, warmup " +
                "3x1s'\" — quotes inside the property are honoured (splitFloorArgs)"
        }
        require(i + 1 < argv.size) { "flag '$flag' has no value" }
        map[flag.removePrefix("--")] = argv[i + 1]
        i += 2
    }
    return map
}

/**
 * Runs one `floorTool` subcommand and returns the process exit code
 * (`FloorCli`, `computenet-3omz.2`).
 *
 * Exit codes: `0` on success, non-zero on any refusal — the ledger's, or a bad-argument
 * refusal from this file's own flag parsing. Every refusal's text goes to [out] so a
 * runner script can surface it; this object never re-implements or softens one. [main]
 * routes [out] to stderr whenever this returns non-zero, so a refusal never lands on the
 * stdout a runner script reads a rendered block from.
 */
object FloorCli {

    private const val USAGE = """usage:
  floorTool plan     --ledger <dir> --class <SimpleName> --jar <bench-jmh.jar>
  floorTool next     --ledger <dir> --jar <bench-jmh.jar>
  floorTool ingest   --ledger <dir> --results <jmh.csv> --log <run.log> --load <1min-load> --cores <n> --harness-sha <sha> --jar <bench-jmh.jar> [--threshold <value>]
  floorTool status   --ledger <dir>
  floorTool render   --ledger <dir> --derived-on <iso-date> --jmh-config <text> [--harness-sha <sha>]
  floorTool render   --existing <SimpleName>

plan starts a new ledger from the class's own -lp enumeration, refused unless the row
count matches EXPECTED_PLAN_ROW_COUNTS. next prints the next outstanding unit to measure,
sized to fit a short quiesced window without altering any JMH knob. ingest feeds one
finished unit's artifacts to the ledger; its --harness-sha is the working tree HEAD read
immediately before that unit's invocation, checked against --jar's OWN stamped build
provenance (`Harness-Commit-Sha`, written by :bench:jmhJar) — a disagreement is the
stale-jar case and is refused, and the stamped sha, not the working-tree one, is what gets
recorded per unit (computenet-7doz). status reports per-row completeness, the measuring
JVM(s) and the harness sha(s) seen. render prints the ClassNoiseFloor(...) call and the
findings.md block for a COMPLETE, single-JVM, single-harness-sha ledger, publishing the
sha the units themselves attest — a --harness-sha passed to render is CHECKED against
them, not substituted for them, and is needed only for a v1 ledger whose units recorded
none. The block carries the units' gathering window, so the single derivedOn date cannot
be read as the span. Or (with --existing) for an already-committed
CLASS_NOISE_FLOOR_DERIVATIONS entry. Every refusal is the ledger's own except --jar
resolution below, which this tool adds itself. Not reachable from check, build or test.

A relative --jar is tried against the working directory first, then against the
repository root (the nearest ancestor with settings.gradle.kts) -- so the repo-root-
relative path this usage block and derive-class-floor.sh print (bench/build/libs/
bench-jmh.jar) resolves correctly even though :bench:floorTool's own working directory
is bench/, not the repo root (computenet-x9e.15). A "jar not found" refusal names every
absolute path it tried. An absolute --jar bypasses both attempts and is always safest.

Through Gradle the whole command line is one property and is tokenised by splitFloorArgs,
which honours quoting, so a value containing spaces must be quoted INSIDE it:
  ./gradlew :bench:floorTool -PfloorArgs="render --ledger <dir> --derived-on <iso-date> \
      --jmh-config 'forks=1, warmup 3x1s, measurement 5x1s'""""

    /**
     * The prefix every refusal carries, exactly once.
     *
     * The ledger's own messages already open with it — they are written to be reprinted
     * verbatim by `derive-class-floor.sh`, which greps for `^REFUSED:` — so prefixing
     * unconditionally produced `REFUSED: REFUSED: ...` (`computenet-71hu`). Stripping here
     * rather than at the throw sites keeps the ledger's messages self-describing for any
     * caller that does not route through this class.
     */
    private const val REFUSAL_PREFIX = "REFUSED: "

    private fun refusal(message: String): String =
        if (message.startsWith(REFUSAL_PREFIX)) message else "$REFUSAL_PREFIX$message"

    fun run(argv: Array<String>, out: Appendable): Int {
        // One element is how `-PfloorArgs` arrives: Gradle can only deliver a single
        // string, so it is tokenised here, where quoting survives (see [splitFloorArgs]).
        // A single element that is already one token — `--help`, `status` — tokenises to
        // itself, so nothing about direct invocation changes.
        val args = try {
            if (argv.size == 1) splitFloorArgs(argv[0]) else argv.toList()
        } catch (e: Exception) {
            out.appendLine(refusal(e.message ?: e.toString()))
            return 1
        }
        val command = args.firstOrNull()
        if (command == null) {
            out.appendLine(USAGE)
            return 1
        }
        if (command == "--help" || command == "-h") {
            out.appendLine(USAGE)
            return 0
        }
        val rest = args.drop(1)
        return try {
            when (command) {
                "plan" -> runPlan(rest, out)
                "next" -> runNext(rest, out)
                "ingest" -> runIngest(rest, out)
                "status" -> runStatus(rest, out)
                "render" -> runRender(rest, out)
                else -> {
                    out.appendLine("unknown subcommand '$command'")
                    out.appendLine(USAGE)
                    1
                }
            }
        } catch (e: Exception) {
            out.appendLine(refusal(e.message ?: e.toString()))
            1
        }
    }

    private fun required(map: Map<String, String>, name: String): String =
        map[name] ?: throw IllegalArgumentException("missing required --$name")

    private fun runPlan(argv: List<String>, out: Appendable): Int {
        val map = parseFlags(argv)
        val ledgerDir = File(required(map, "ledger"))
        val benchmarkClass = required(map, "class")
        val jar = JarPath.resolve(required(map, "jar"))

        val enumeration = EnumerationRoute.enumerate(jar, benchmarkClass)
        val plan = DerivationPlan.of(
            benchmarkClass = benchmarkClass,
            rows = enumeration.rows,
            enumerationProvenance = enumeration.provenance,
        )
        val ledger = FloorDerivationLedger.start(ledgerDir, plan)
        out.appendLine(
            "Planned '$benchmarkClass': ${plan.rows.size} rows, ledger at ${ledger.file}"
        )
        out.appendLine(ledger.describeProgress())
        return 0
    }

    private fun runNext(argv: List<String>, out: Appendable): Int {
        val map = parseFlags(argv)
        val ledger = FloorDerivationLedger.load(File(required(map, "ledger")))
        val jar = JarPath.resolve(required(map, "jar"))

        val unit = NextPlanner.next(ledger) { benchmarkClass, method ->
            UnitSizing.estimateRowSeconds(jar, benchmarkClass, method)
        }
        if (unit == null) {
            out.appendLine(
                "'${ledger.plan.benchmarkClass}' is complete: ${ledger.describeProgress()}"
            )
            return 0
        }
        out.appendLine("next unit for '${unit.benchmarkClass}': ${unit.describeInvocation()}")
        out.appendLine(
            "rows in this unit: ${unit.rows.size} (${unit.rows.map { it.describe() }.sorted()})"
        )
        out.appendLine(
            "estimated seconds: ${unit.estimatedSeconds} (target " +
                "${UnitSizing.TARGET_UNIT_SECONDS})"
        )
        val counts = ledger.observationCounts()
        val selected = unit.rows.toSet()
        val remainingAfter = ledger.plan.rows.count { row ->
            val projected = (counts[row] ?: 0) + if (row in selected) 1 else 0
            projected < CLASS_FLOOR_OBSERVATIONS_PER_ROW
        }
        out.appendLine(
            "rows outstanding after this unit (if ingested): $remainingAfter of " +
                "${ledger.plan.rows.size}"
        )
        return 0
    }

    private fun runIngest(argv: List<String>, out: Appendable): Int {
        val map = parseFlags(argv)
        val ledger = FloorDerivationLedger.load(File(required(map, "ledger")))
        val resultsFile = File(required(map, "results"))
        val logFile = File(required(map, "log"))
        val loadText = required(map, "load")
        val coresText = required(map, "cores")
        val jar = JarPath.resolve(required(map, "jar"))

        val load = loadText.toDoubleOrNull()
            ?: throw IllegalArgumentException("--load must be a number, was '$loadText'")
        val cores = coresText.toIntOrNull()
            ?: throw IllegalArgumentException("--cores must be a whole number, was '$coresText'")

        require(resultsFile.isFile) { "results file not found: ${resultsFile.absolutePath}" }
        require(logFile.isFile) { "log file not found: ${logFile.absolutePath}" }
        require(jar.isFile) { "jar not found: ${jar.absolutePath}" }

        // The prescribed pattern (`computenet-akfa`): '^# VM version', never a looser
        // match — a looser one hits JMH's sun.misc.Unsafe warning line first.
        val bannerPattern = Regex("^# VM version")
        val banner = logFile.readLines().firstOrNull { bannerPattern.containsMatchIn(it) }
            ?: throw FloorLedgerException(
                "no '# VM version' banner line found in ${logFile.absolutePath}; the " +
                    "measuring JVM cannot be established from this log"
            )

        // Required, not defaulted (`computenet-tdby`). This is the working tree's HEAD,
        // read by the caller (`derive-class-floor.sh`) immediately before the JMH
        // invocation — NOT what gets recorded as the unit's harnessSha any more
        // (`computenet-7doz`). It exists here only to be checked against the jar's own
        // stamp below: a default would be a sha this tool invented, and the whole defect
        // being closed is a harness sha nobody attested being published as if a unit had
        // measured under it.
        val workingTreeSha = required(map, "harness-sha")

        // The jar's own build provenance (`computenet-7doz`) — written by `:bench:jmhJar`
        // itself, not supplied by any caller. Absent entirely (no manifest, or a manifest
        // predating this stamp): refused, naming the rebuild command, so the unattested
        // path can never be reached silently.
        val stampedSha = HarnessJarStamp.read(jar)
            ?: throw FloorLedgerException(
                "REFUSED: ${jar.absolutePath} carries no " +
                    "'${HarnessJarStamp.MANIFEST_ATTRIBUTE}' manifest attribute — either it " +
                    "predates this stamp or was not produced by ':bench:jmhJar'. A jar with " +
                    "no attested build provenance cannot be recorded as measuring anything. " +
                    "Rebuild it: ./gradlew :bench:jmhJar"
            )

        // The stale-jar case (`computenet-7doz`): the jar on disk was built from a
        // different checkout than the one this unit's invocation actually ran against.
        // Mirrors the mixed-harness-sha refusal's specificity (FloorDerivationLedger.kt's
        // `render`) — names both shas and the fix — but fires here, at ingest, rather than
        // waiting for a later render: a stale jar is not "two units disagree", it is "this
        // one unit's own provenance is already broken", and there is nothing to gain by
        // deferring that.
        if (stampedSha != workingTreeSha) {
            throw FloorLedgerException(
                "REFUSED: unit '${resultsFile.nameWithoutExtension}' ran against " +
                    "${jar.absolutePath}, built at '$stampedSha', but the working tree HEAD " +
                    "read immediately before the invocation was '$workingTreeSha'. This is " +
                    "the stale-jar case: the jar was not rebuilt after the checkout changed, " +
                    "so it measures code from a commit other than the one now checked out. " +
                    "Rebuild the jar at this checkout (./gradlew :bench:jmhJar) and re-run " +
                    "this unit, or check out '$stampedSha' to match what the jar actually " +
                    "measures."
            )
        }
        // The recorded provenance, in preference to the working-tree sha above: the jar's
        // own stamp describes what was actually measured, and by this point the two are
        // known to agree (the mismatch above already refused otherwise) — spelled out
        // explicitly rather than reusing workingTreeSha, so a future change to either
        // branch above cannot silently start recording the wrong one.
        val harnessSha = stampedSha

        // Optional, defaulting to the computed rule (`computenet-b5xt`). The default
        // exists so a caller with nothing else to attest is not forced to invent a
        // number, but a caller that DID gate on something -- `derive-class-floor.sh`'s
        // own THRESHOLD variable -- passes it here, and GateReading's own construction
        // check (`abs(attestedThreshold - threshold) <= 0.005`) refuses a mismatch. Prior
        // to this flag, ingest recomputed the threshold from --cores unconditionally, so
        // that check compared the ledger's rule against a value derived from the same
        // rule and could never fire.
        val thresholdText = map["threshold"]
        val threshold = if (thresholdText != null) {
            thresholdText.toDoubleOrNull()
                ?: throw IllegalArgumentException(
                    "--threshold must be a number, was '$thresholdText'"
                )
        } else {
            Math.round(cores * QUIESCED_LOAD_FACTOR * 100.0) / 100.0
        }
        val unit = UnitAttestation(
            unitId = resultsFile.nameWithoutExtension,
            measuringJvm = banner,
            gate = GateReading(oneMinuteLoad = load, cores = cores, attestedThreshold = threshold),
            timestamp = DateTimeFormatter.ISO_INSTANT.format(
                Instant.ofEpochMilli(logFile.lastModified()).atZone(ZoneOffset.UTC)
            ),
            harnessSha = harnessSha,
        )
        val warnings = ledger.ingest(unit, resultsFile.readText())
        out.appendLine("ingested unit '${unit.unitId}': ${ledger.describeProgress()}")
        warnings.forEach { out.appendLine("WARNING: $it") }
        return 0
    }

    private fun runStatus(argv: List<String>, out: Appendable): Int {
        val map = parseFlags(argv)
        val ledger = FloorDerivationLedger.load(File(required(map, "ledger")))

        out.appendLine(ledger.describeProgress())
        val counts = ledger.observationCounts()
        ledger.plan.rows.sortedBy { it.describe() }.forEach { row ->
            out.appendLine("  ${row.describe()}: ${counts[row]}/$CLASS_FLOOR_OBSERVATIONS_PER_ROW")
        }
        val jvms = ledger.measuringJvms()
        if (jvms.isEmpty()) {
            out.appendLine("measuring JVM(s) seen: none yet")
        } else {
            out.appendLine("measuring JVM(s) seen: ${jvms.joinToString("; ")}")
        }
        // Reported beside the JVM(s) because it is refused beside them (`computenet-tdby`).
        val shas = ledger.harnessShas()
        val unattested = ledger.unattestedHarnessUnits()
        out.appendLine(
            when {
                shas.isEmpty() && unattested.isEmpty() -> "harness sha(s) seen: none yet"
                shas.isEmpty() ->
                    "harness sha(s) seen: none — all ${unattested.size} unit(s) predate " +
                        "the field (v1 ledger)"
                unattested.isEmpty() -> "harness sha(s) seen: ${shas.joinToString("; ")}"
                else ->
                    "harness sha(s) seen: ${shas.joinToString("; ")} " +
                        "(plus ${unattested.size} v1 unit(s) recording none)"
            }
        )
        ledger.gatheringWindow()?.let { out.appendLine(it.describe()) }
        return 0
    }

    private fun runRender(argv: List<String>, out: Appendable): Int {
        val map = parseFlags(argv)
        val existing = map["existing"]
        var ledgerRendered: FloorDerivationLedger? = null
        val record = if (existing != null) {
            CLASS_NOISE_FLOOR_DERIVATIONS.firstOrNull { it.benchmarkClass == existing }
                ?: throw FloorLedgerException(
                    "no entry for '$existing' in CLASS_NOISE_FLOOR_DERIVATIONS; the " +
                        "classes with one are " +
                        CLASS_NOISE_FLOOR_DERIVATIONS.map { it.benchmarkClass }.sorted()
                )
        } else {
            val ledger = FloorDerivationLedger.load(File(required(map, "ledger")))
            ledgerRendered = ledger
            val derived = ledger.render(
                derivedOn = required(map, "derived-on"),
                // OPTIONAL now (`computenet-tdby`): the units attest the sha they measured
                // at, and one supplied here is checked against theirs rather than
                // overriding it. It remains required for a v1 ledger, whose units record
                // none — the ledger's own refusal says so.
                harnessCommitSha = map["harness-sha"],
                jmhConfig = required(map, "jmh-config"),
            )
            // How the observations were gathered, read off the ledger rather than assumed
            // (`computenet-71hu`). The rule is exact, not a heuristic: `render` has already
            // refused unless EVERY planned row carries exactly
            // CLASS_FLOOR_OBSERVATIONS_PER_ROW observations, and `ingest` refuses a unit
            // that measures one row twice — so a ledger of exactly that many units can
            // only be one in which every unit measured every row, which is a sequential
            // whole-class run. Any other count means at least one unit measured a proper
            // subset, and calling that "N sequential repeat runs" is the false sentence
            // this replaces.
            val units = ledger.units.size
            derived.copy(
                assembly = if (units == CLASS_FLOOR_OBSERVATIONS_PER_ROW) {
                    DerivationAssembly.WholeClassRuns(runs = units)
                } else {
                    DerivationAssembly.UnitAssembled(units = units)
                },
            )
        }
        // What the record's single fields cannot say, said before the block the operator
        // pastes rather than left to a caveat somewhere else (`computenet-tdby`).
        ledgerRendered?.renderWarnings()?.forEach { out.appendLine("WARNING: $it") }
        out.appendLine(renderConstructorCall(record))
        out.appendLine()
        out.appendLine("--- findings.md block ---")
        out.append(renderDerivation(record))
        // The gathering window goes INSIDE the block, not beside it. `derivedOn` is one
        // date and a decomposed set spans many; a span printed only on the console is a
        // span that never reaches findings.md, which is the file every later reader
        // actually consults. `render --existing` has no ledger and so appends nothing —
        // which is also what keeps it byte-identical to `renderDerivation`.
        ledgerRendered?.gatheringWindow()?.let { out.appendLine(it.describe()) }
        return 0
    }
}

/**
 * Process entry point for the `:bench:floorTool` task.
 *
 * A successful run's output goes to stdout; a refusal's goes to **stderr**, which is what
 * `computenet-3omz.2` prescribes ("non-zero on any refusal, refusal text on stderr — the
 * runner script gates on them"). The successor runner script therefore reads a rendered
 * block off stdout without a refusal's text ever contaminating it, and can log a refusal
 * separately. The split is by exit code rather than per line because [FloorCli.run]
 * already returns non-zero for exactly the refusing cases.
 */
fun main(argv: Array<String>) {
    val out = StringBuilder()
    val code = FloorCli.run(argv, out)
    if (code == 0) print(out) else System.err.print(out)
    exitProcess(code)
}
