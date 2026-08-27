package civictech.bench

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * `floorTool`'s subcommands, driven as functions rather than through the Gradle task or a
 * spawned JVM (`computenet-3omz.2`).
 *
 * **`plan`'s enumeration route (`-lp` against a real jar) is exercised only at the
 * PARSING layer**, via [EnumerationRoute.parse] against canned `-lp` output captured from
 * a real run (see [FloorTool]'s KDoc for the verification transcript). Spawning the real
 * subprocess would need `bench-jmh.jar` built first, and `:bench:jmhJar` is deliberately
 * OUTSIDE `check`/`build`/`test` (`bench/build.gradle.kts`) — making `:bench:test` depend
 * on it would reintroduce exactly the lifecycle reachability that isolation exists to
 * prevent, for a fast unit-test module the module's own header describes as
 * "sub-second". The other subcommands (`next`, `ingest`, `status`, `render`) never touch
 * a jar or a subprocess and are exercised end to end through [FloorCli.run].
 *
 * **This suite owns no policy either.** Every refusal it asserts is
 * [FloorDerivationLedger]'s own — carried through [FloorCli.run] unchanged — never a
 * rule this file invents.
 */
class FloorToolTest {

    // -----------------------------------------------------------------------------------
    // EnumerationRoute.parse — the `-lp` output shape, pinned against a real transcript.
    // -----------------------------------------------------------------------------------

    private val cellFootprintLpOutput = """
        Benchmarks:
        civictech.bench.micro.CellFootprintBenchmark.realSnapshot
          param "scale" = {N1E3, N1E4, N1E5}
          param "family" = {SET_CELL, MAP_CELL, OR_MAP_CELL, KEYED_SET_CELL, LIST_CELL, COUNTER_CELL, PN_COUNTER_CELL}
    """.trimIndent()

    @Test
    fun `parses -lp output into the full cross-product of a method's params`() {
        val rows = EnumerationRoute.parse(cellFootprintLpOutput, "CellFootprintBenchmark", "cmd")

        rows.size shouldBe 21
        rows.toSet() shouldBe (
            listOf("N1E3", "N1E4", "N1E5").flatMap { scale ->
                listOf(
                    "SET_CELL", "MAP_CELL", "OR_MAP_CELL", "KEYED_SET_CELL",
                    "LIST_CELL", "COUNTER_CELL", "PN_COUNTER_CELL",
                ).map { family ->
                    RowKey.of("realSnapshot", mapOf("scale" to scale, "family" to family))
                }
            }
        ).toSet()
    }

    @Test
    fun `parses a method with no @Param as a single row`() {
        val output = "Benchmarks:\ncivictech.bench.micro.SmokeBenchmark.baseline\n"
        val rows = EnumerationRoute.parse(output, "SmokeBenchmark", "cmd")
        rows shouldBe listOf(RowKey.of("baseline", emptyMap()))
    }

    @Test
    fun `parses multiple methods, each with its own params`() {
        val output = """
            Benchmarks:
            civictech.bench.micro.OperatorThroughputBenchmark.real
              param "subject" = {A, B}
              param "direction" = {INSERT, RETRACT}
            civictech.bench.micro.OperatorThroughputBenchmark.sim
              param "subject" = {A, B}
              param "direction" = {INSERT, RETRACT}
        """.trimIndent()
        val rows = EnumerationRoute.parse(output, "OperatorThroughputBenchmark", "cmd")
        rows.size shouldBe 8
        rows.map { it.method }.toSet() shouldBe setOf("real", "sim")
    }

    @Test
    fun `refuses a row belonging to another class, naming why`() {
        val output = "Benchmarks:\ncivictech.bench.micro.OtherBenchmark.foo\n"
        val refusal = shouldThrow<FloorLedgerException> {
            EnumerationRoute.parse(output, "CellFootprintBenchmark", "cmd")
        }
        refusal.message!! shouldContain "matched more than the intended class"
    }

    @Test
    fun `refuses a line it does not recognise, naming the documented fallback`() {
        val output = "Benchmarks:\nnot a benchmark line at all\n"
        val refusal = shouldThrow<FloorLedgerException> {
            EnumerationRoute.parse(output, "CellFootprintBenchmark", "cmd")
        }
        refusal.message!! shouldContain "fallback route"
    }

    // -----------------------------------------------------------------------------------
    // JarPath.resolve — the doubled bench/bench/ path (computenet-x9e.15).
    //
    // `:bench:floorTool`'s JavaExec runs with `bench/` as its working directory, so a
    // relative `--jar bench/build/libs/bench-jmh.jar` typed at the repo root — exactly
    // what this file's own USAGE block and derive-class-floor.sh print — resolved naively
    // against that working directory becomes `bench/bench/build/libs/bench-jmh.jar`. These
    // cases fake the two directories with @TempDir rather than depending on the real repo
    // layout or a built jar, so they run as fast unit tests.
    // -----------------------------------------------------------------------------------

    @Test
    fun `an absolute --jar is returned unchanged, even if it does not exist`(
        @TempDir cwd: File,
    ) {
        val absolute = File(cwd, "somewhere/bench-jmh.jar")
        JarPath.resolve(absolute.absolutePath, cwd) shouldBe absolute
    }

    @Test
    fun `a relative --jar resolves against the working directory when it exists there`(
        @TempDir cwd: File,
    ) {
        val jar = File(cwd, "bench/build/libs/bench-jmh.jar")
        jar.parentFile.mkdirs()
        jar.writeText("not a real jar, just needs to exist")

        JarPath.resolve("bench/build/libs/bench-jmh.jar", cwd) shouldBe jar
    }

    @Test
    fun `a repo-root-relative --jar resolves against the repo root when the working directory is one level in`(
        @TempDir repoRoot: File,
    ) {
        // Mirrors the real shape: repoRoot/settings.gradle.kts marks the root,
        // repoRoot/bench is floorTool's actual working directory, and the jar the
        // operator's copied command names lives at repoRoot/bench/build/libs/*.
        File(repoRoot, "settings.gradle.kts").writeText("")
        val cwd = File(repoRoot, "bench").apply { mkdirs() }
        val jar = File(repoRoot, "bench/build/libs/bench-jmh.jar")
        jar.parentFile.mkdirs()
        jar.writeText("not a real jar, just needs to exist")

        // Resolved against cwd (bench/) first: bench/bench/build/libs/bench-jmh.jar does
        // NOT exist, so this only passes if the repo-root fallback ran.
        JarPath.resolve("bench/build/libs/bench-jmh.jar", cwd) shouldBe jar
    }

    @Test
    fun `a --jar found at neither attempt names both absolute paths it tried`(
        @TempDir repoRoot: File,
    ) {
        File(repoRoot, "settings.gradle.kts").writeText("")
        val cwd = File(repoRoot, "bench").apply { mkdirs() }
        val cwdAttempt = File(cwd, "bench/build/libs/missing.jar")
        val rootAttempt = File(repoRoot, "bench/build/libs/missing.jar")

        val refusal = shouldThrow<IllegalArgumentException> {
            JarPath.resolve("bench/build/libs/missing.jar", cwd)
        }
        refusal.message!! shouldContain cwdAttempt.path
        refusal.message!! shouldContain rootAttempt.path
    }

    @Test
    fun `a --jar with no repo root above the working directory names only the working-directory attempt`(
        @TempDir cwd: File,
    ) {
        // cwd has no settings.gradle.kts anywhere above it up to the filesystem root in
        // this fake tree, so findRepoRoot has nothing to fall back to.
        val refusal = shouldThrow<IllegalArgumentException> {
            JarPath.resolve("build/libs/missing.jar", cwd)
        }
        refusal.message!! shouldContain File(cwd, "build/libs/missing.jar").path
    }

    // -----------------------------------------------------------------------------------
    // Shared CLI fixtures. Mirrors FloorDerivationLedgerTest's own fixtures, kept
    // independent so this file drives the ledger only through FloorCli.
    // -----------------------------------------------------------------------------------

    // `FloorCli`'s `load` calls (ingest/status/render/next) never accept an override
    // expectedRowCounts — accepting one from the command line would be exactly the
    // vacuous-completeness hole EXPECTED_PLAN_ROW_COUNTS exists to close, so the CLI
    // always checks against the LIVE table. A test ledger therefore has to name one of
    // the table's real classes; the row VALUES are otherwise free (nothing validates a
    // RowKey's params against the real class's actual @Param enum). BoundedReadBenchmark
    // (2 methods x 3, pre-registered as 6) gives two methods to exercise `next`'s
    // method-then-param batching the same way the CellFootprintBenchmark-shaped fixture
    // in FloorDerivationLedgerTest does with three.
    private val syntheticClass = "BoundedReadBenchmark"
    private val syntheticRows = listOf(
        RowKey.of("alpha", mapOf("scale" to "N1E3")),
        RowKey.of("alpha", mapOf("scale" to "N1E4")),
        RowKey.of("alpha", mapOf("scale" to "N1E5")),
        RowKey.of("beta", mapOf("scale" to "N1E3")),
        RowKey.of("beta", mapOf("scale" to "N1E4")),
        RowKey.of("beta", mapOf("scale" to "N1E5")),
    )
    private val jdk21 = "# VM version: JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS"

    /** The harness sha the fixture units are ingested at unless a case varies it. */
    private val harnessSha = "abcdef012"
    private val jbr25 = "# VM version: JDK 25.0.2, OpenJDK 64-Bit Server VM, 25.0.2+12-b1073.1"

    private fun startSyntheticLedger(dir: File): FloorDerivationLedger =
        FloorDerivationLedger.start(
            dir,
            DerivationPlan.of(
                benchmarkClass = syntheticClass,
                rows = syntheticRows,
                enumerationProvenance = "java -jar bench-jmh.jar -lp 'BoundedReadBenchmark'",
            ),
        )

    private fun csv(
        rows: List<Pair<RowKey, Double>>,
        benchmarkClass: String = syntheticClass,
        paramColumns: List<String> = listOf("scale"),
    ): String = buildString {
        appendLine(
            (listOf("Benchmark", "Mode", "Threads", "Samples", "Score", "Score Error (99.9%)", "Unit") +
                paramColumns.map { "Param: $it" })
                .joinToString(",") { "\"$it\"" }
        )
        rows.forEach { (row, dispersion) ->
            appendLine(
                (listOf(
                    "civictech.bench.micro.$benchmarkClass.${row.method}",
                    "avgt",
                    "1",
                    "5",
                    "1000.0",
                    (1000.0 * dispersion).toString(),
                    "us/op",
                ) + paramColumns.map { row.params[it] ?: "" })
                    .joinToString(",") { "\"$it\"" }
            )
        }
    }

    private fun writeLog(dir: File, name: String, banner: String = jdk21): File {
        val log = File(dir, name)
        log.writeText(
            "# JMH version: 1.37\n" +
                "# VM invoker: /usr/bin/java\n" +
                "# Warmup: 5 iterations, 1 s each\n" +
                "$banner (build 21.0.5+11-LTS, mixed mode)\n" +
                "# Fork: 1 of 1\n"
        )
        // `ingest` takes a unit's timestamp from its log's mtime, and since
        // computenet-tdby the rendered block carries the units' gathering window — so an
        // un-pinned mtime puts the wall-clock time of the test run inside the output.
        // Pinned here rather than left to the filesystem: a case that wants a span sets
        // its own stamp, and every other case gets one deterministic instant.
        log.setLastModified(java.time.Instant.parse("2026-08-27T09:00:00Z").toEpochMilli())
        return log
    }

    private fun writeResults(dir: File, name: String, content: String): File {
        val file = File(dir, name)
        file.writeText(content)
        return file
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val out = StringBuilder()
        val code = FloorCli.run(args.toList().toTypedArray(), out)
        return code to out.toString()
    }

    // -----------------------------------------------------------------------------------
    // --help / no args.
    // -----------------------------------------------------------------------------------

    @Test
    fun `--help prints usage and exits zero`() {
        val (code, out) = run("--help")
        code shouldBe 0
        out shouldContain "floorTool plan"
        out shouldContain "floorTool next"
        out shouldContain "floorTool ingest"
        out shouldContain "floorTool status"
        out shouldContain "floorTool render"
    }

    @Test
    fun `no arguments prints usage and exits non-zero`() {
        val (code, out) = run()
        code shouldBe 1
        out shouldContain "usage:"
    }

    // -----------------------------------------------------------------------------------
    // ingest / status happy path.
    // -----------------------------------------------------------------------------------

    @Test
    fun `ingest feeds one unit to the ledger and status reports progress`(@TempDir dir: File) {
        val ledgerDir = File(dir, "ledger")
        startSyntheticLedger(ledgerDir)

        writeLog(dir, "run-1.log")
        writeResults(dir, "run-1.csv", csv(syntheticRows.map { it to 0.02 }))

        val (ingestCode, ingestOut) = run(
            "ingest", "--ledger", ledgerDir.path,
            "--results", File(dir, "run-1.csv").path,
            "--log", File(dir, "run-1.log").path,
            "--load", "1.2", "--cores", "16", "--harness-sha", "abcdef012",
        )
        ingestCode shouldBe 0
        ingestOut shouldContain "ingested unit 'run-1'"

        val (statusCode, statusOut) = run("status", "--ledger", ledgerDir.path)
        statusCode shouldBe 0
        statusOut shouldContain "1/3"
        statusOut shouldContain jdk21
    }

    @Test
    fun `ingest refuses a runner that attested a threshold disagreeing with cores x 0_25`(
        @TempDir dir: File,
    ) {
        val ledgerDir = File(dir, "ledger")
        startSyntheticLedger(ledgerDir)

        writeLog(dir, "run-1.log")
        writeResults(dir, "run-1.csv", csv(syntheticRows.map { it to 0.02 }))

        // 16 cores x 0.25 = 4.0. A runner that actually gated on 9.0 (e.g. a stale or
        // hand-edited threshold) must be refused, not silently accepted as if it had
        // gated on the CLI's own recomputation (computenet-b5xt).
        val (code, out) = run(
            "ingest", "--ledger", ledgerDir.path,
            "--results", File(dir, "run-1.csv").path,
            "--log", File(dir, "run-1.log").path,
            "--load", "1.2", "--cores", "16", "--harness-sha", "abcdef012",
            "--threshold", "9.0",
        )
        code shouldBe 1
        out shouldContain "attested gate threshold 9.0"
    }

    @Test
    fun `ingest refuses a log with no VM version banner`(@TempDir dir: File) {
        val ledgerDir = File(dir, "ledger")
        startSyntheticLedger(ledgerDir)

        val log = File(dir, "bannerless.log")
        // Deliberately carries JMH's sun.misc.Unsafe warning, which a looser banner
        // pattern than '^# VM version' would mistake for the banner (computenet-akfa).
        log.writeText(
            "WARNING: sun.misc.Unsafe::objectFieldOffset has been called\n" +
                "# JMH version: 1.37\n"
        )
        writeResults(dir, "run-1.csv", csv(syntheticRows.map { it to 0.02 }))

        val (code, out) = run(
            "ingest", "--ledger", ledgerDir.path,
            "--results", File(dir, "run-1.csv").path,
            "--log", log.path,
            "--load", "1.2", "--cores", "16", "--harness-sha", "abcdef012",
        )
        code shouldBe 1
        out shouldContain "REFUSED"
        out shouldContain "no '# VM version' banner"
    }

    // -----------------------------------------------------------------------------------
    // render.
    // -----------------------------------------------------------------------------------

    @Test
    fun `render on a complete ledger prints the constructor call and the findings block`(
        @TempDir dir: File,
    ) {
        val ledgerDir = File(dir, "ledger")
        startSyntheticLedger(ledgerDir)
        listOf(0.01, 0.02, 0.03).forEachIndexed { index, dispersion ->
            writeLog(dir, "run-${index + 1}.log")
            writeResults(dir, "run-${index + 1}.csv", csv(syntheticRows.map { it to dispersion }))
            run(
                "ingest", "--ledger", ledgerDir.path,
                "--results", File(dir, "run-${index + 1}.csv").path,
                "--log", File(dir, "run-${index + 1}.log").path,
                "--load", "1.2", "--cores", "16", "--harness-sha", "abcdef012",
            )
        }

        val (code, out) = run(
            "render", "--ledger", ledgerDir.path,
            "--derived-on", "2026-08-27",
            "--jmh-config", "mode=AverageTime forks=1",
        )
        code shouldBe 0
        out shouldContain "ClassNoiseFloor("
        out shouldContain "benchmarkClass = \"BoundedReadBenchmark\""
        out shouldContain "--- findings.md block ---"
        out shouldContain "per-class noise floor for `BoundedReadBenchmark`"
    }

    @Test
    fun `render refuses an incomplete ledger, naming the outstanding rows`(@TempDir dir: File) {
        val ledgerDir = File(dir, "ledger")
        startSyntheticLedger(ledgerDir)
        writeLog(dir, "run-1.log")
        writeResults(dir, "run-1.csv", csv(syntheticRows.map { it to 0.02 }))
        run(
            "ingest", "--ledger", ledgerDir.path,
            "--results", File(dir, "run-1.csv").path,
            "--log", File(dir, "run-1.log").path,
            "--load", "1.2", "--cores", "16", "--harness-sha", "abcdef012",
        )

        val (code, out) = run(
            "render", "--ledger", ledgerDir.path,
            "--derived-on", "2026-08-27", "--jmh-config", "x",
        )
        code shouldBe 1
        out shouldContain "REFUSED"
        out shouldContain "row set is incomplete"
    }

    @Test
    fun `render refuses a ledger spanning two measuring JVMs`(@TempDir dir: File) {
        val ledgerDir = File(dir, "ledger")
        startSyntheticLedger(ledgerDir)
        listOf(jdk21, jdk21, jbr25).forEachIndexed { index, banner ->
            writeLog(dir, "run-${index + 1}.log", banner)
            writeResults(dir, "run-${index + 1}.csv", csv(syntheticRows.map { it to 0.02 }))
            run(
                "ingest", "--ledger", ledgerDir.path,
                "--results", File(dir, "run-${index + 1}.csv").path,
                "--log", File(dir, "run-${index + 1}.log").path,
                "--load", "1.2", "--cores", "16", "--harness-sha", "abcdef012",
            )
        }

        val (code, out) = run(
            "render", "--ledger", ledgerDir.path,
            "--derived-on", "2026-08-27", "--jmh-config", "x",
        )
        code shouldBe 1
        out shouldContain "REFUSED"
        out shouldContain "measuring"
    }

    @Test
    fun `render --existing reproduces renderDerivation byte-for-byte for a committed entry`() {
        val (code, out) = run("render", "--existing", "CellFootprintBenchmark")
        code shouldBe 0

        val record = CLASS_NOISE_FLOOR_DERIVATIONS.first { it.benchmarkClass == "CellFootprintBenchmark" }
        val block = out.substringAfter("--- findings.md block ---\n")
        block shouldBe renderDerivation(record)
    }

    // -----------------------------------------------------------------------------------
    // computenet-71hu — the three defects computenet-3omz.4's review found in this
    // surface, each pinned here.
    // -----------------------------------------------------------------------------------

    /** Ingests [csvContent] as one more unit, through the CLI, and returns its exit code. */
    private fun ingestUnit(dir: File, name: String, csvContent: String, banner: String = jdk21): Int {
        writeLog(dir, "$name.log", banner)
        writeResults(dir, "$name.csv", csvContent)
        return run(
            "ingest", "--ledger", File(dir, "ledger").path,
            "--results", File(dir, "$name.csv").path,
            "--log", File(dir, "$name.log").path,
            "--load", "1.2", "--cores", "16", "--harness-sha", harnessSha,
        ).first
    }

    /**
     * Three units, each covering every planned row: the shape the original three
     * sequential whole-class executions produced, and the only shape for which
     * "N sequential repeat runs" is a true sentence.
     */
    private fun completeAsWholeClassRuns(dir: File) {
        startSyntheticLedger(File(dir, "ledger"))
        listOf(0.01, 0.02, 0.03).forEachIndexed { index, dispersion ->
            ingestUnit(dir, "run-${index + 1}", csv(syntheticRows.map { it to dispersion })) shouldBe 0
        }
    }

    /**
     * Six units, each covering one method's three rows — `next`'s own method-then-param
     * batching, run as six separate processes. The derived numbers are identical to
     * [completeAsWholeClassRuns]'s; only how they were gathered differs.
     */
    private fun completeAsUnitAssembled(dir: File) {
        startSyntheticLedger(File(dir, "ledger"))
        var n = 0
        listOf(0.01, 0.02, 0.03).forEach { dispersion ->
            listOf("alpha", "beta").forEach { method ->
                n += 1
                val rows = syntheticRows.filter { it.method == method }.map { it to dispersion }
                ingestUnit(dir, "unit-$n", csv(rows)) shouldBe 0
            }
        }
    }

    private fun renderComplete(dir: File): Pair<Int, String> = run(
        "render", "--ledger", File(dir, "ledger").path,
        "--derived-on", "2026-08-27",
        // No --harness-sha: the units attest it (`computenet-tdby`).
        "--jmh-config", "mode=AverageTime forks=1",
    )

    @Test
    fun `a set assembled from many units is not described as sequential repeat runs`(
        @TempDir dir: File,
    ) {
        completeAsUnitAssembled(dir)

        val (code, out) = renderComplete(dir)
        code shouldBe 0
        // The defect: this set was six invocations in six processes, and the block called
        // it three sequential repeat runs.
        out shouldNotContain "sequential repeat runs"
        out shouldContain "6 measuring units"
        out shouldContain "3 observations"
    }

    @Test
    fun `a set of whole-class runs is still described as sequential repeat runs`(
        @TempDir dir: File,
    ) {
        completeAsWholeClassRuns(dir)

        val (code, out) = renderComplete(dir)
        code shouldBe 0
        out shouldContain "3 sequential repeat runs"
        out shouldNotContain "measuring units"
    }

    @Test
    fun `the two assemblies differ only in description, never in a derived number`(
        @TempDir dir: File,
    ) {
        val wholeDir = File(dir, "whole").also { it.mkdirs() }
        val unitDir = File(dir, "units").also { it.mkdirs() }
        completeAsWholeClassRuns(wholeDir)
        completeAsUnitAssembled(unitDir)

        val whole = renderComplete(wholeDir).second
        val assembled = renderComplete(unitDir).second

        // Every number the block publishes is identical; only the gathering sentence moves.
        val numbers = { text: String ->
            Regex("[0-9]+\\.[0-9]+").findAll(text).map { it.value }.toList()
        }
        numbers(assembled) shouldBe numbers(whole)
    }

    @Test
    fun `a refusal carries exactly one REFUSED prefix`(@TempDir dir: File) {
        startSyntheticLedger(File(dir, "ledger"))
        ingestUnit(dir, "run-1", csv(syntheticRows.map { it to 0.02 })) shouldBe 0

        val (code, out) = renderComplete(dir)
        code shouldBe 1
        out shouldContain "row set is incomplete"
        out shouldNotContain "REFUSED: REFUSED:"
        out.trim().startsWith("REFUSED: ") shouldBe true
    }

    // splitFloorArgs — the `-PfloorArgs` bridge. Gradle can only deliver ONE string, so
    // the tokenising happens here, where a quoted value survives.

    @Test
    fun `splitFloorArgs keeps a quoted value containing spaces in one token`() {
        splitFloorArgs("render --ledger /tmp/x --jmh-config 'forks=1, warmup 3x1s'") shouldBe
            listOf("render", "--ledger", "/tmp/x", "--jmh-config", "forks=1, warmup 3x1s")
    }

    @Test
    fun `splitFloorArgs handles double quotes, escapes and runs of whitespace`() {
        splitFloorArgs("  a   \"b c\"  d\\ e ") shouldBe listOf("a", "b c", "d e")
        splitFloorArgs("'it'\\''s'") shouldBe listOf("it's")
    }

    @Test
    fun `splitFloorArgs refuses an unterminated quote rather than guessing`() {
        val refusal = shouldThrow<IllegalArgumentException> {
            splitFloorArgs("render --jmh-config 'forks=1, warmup 3x1s")
        }
        refusal.message!! shouldContain "unterminated"
    }

    @Test
    fun `a single raw argument carries a spaced --jmh-config through to the block`(
        @TempDir dir: File,
    ) {
        completeAsWholeClassRuns(dir)
        val ledger = File(dir, "ledger").path

        val out = StringBuilder()
        val code = FloorCli.run(
            arrayOf(
                "render --ledger $ledger --derived-on 2026-08-27 --harness-sha abcdef012 " +
                    "--jmh-config 'forks=1, warmup 3x1s, measurement 5x1s'"
            ),
            out,
        )

        code shouldBe 0
        out.toString() shouldContain "JMH: forks=1, warmup 3x1s, measurement 5x1s"
    }

    // -----------------------------------------------------------------------------------
    // computenet-tdby — harness sha and gathering span, through the CLI.
    //
    // The defect this closes, measured against the unfixed tool: three units timestamped
    // 2026-08-20, -08-23 and -08-26 rendered at exit 0 under
    // `harnessCommitSha = "deadbeef"` — a sha no unit had measured under, taken straight
    // from the caller — and the findings block said "Harness: deadbeef · host state
    // quiesced · 3 sequential repeat runs" with the span stated nowhere.
    // -----------------------------------------------------------------------------------

    /** Ingests one unit at [sha], with the log stamped [stamp]. */
    private fun ingestUnitAt(
        dir: File,
        name: String,
        csvContent: String,
        sha: String,
        stamp: String? = null,
    ): Pair<Int, String> {
        val log = writeLog(dir, "$name.log")
        if (stamp != null) log.setLastModified(java.time.Instant.parse(stamp).toEpochMilli())
        writeResults(dir, "$name.csv", csvContent)
        return run(
            "ingest", "--ledger", File(dir, "ledger").path,
            "--results", File(dir, "$name.csv").path,
            "--log", log.path,
            "--load", "1.2", "--cores", "16", "--harness-sha", sha,
        )
    }

    @Test
    fun `ingest without --harness-sha is refused, so a unit's provenance cannot go unrecorded`(
        @TempDir dir: File,
    ) {
        startSyntheticLedger(File(dir, "ledger"))
        writeLog(dir, "run-1.log")
        writeResults(dir, "run-1.csv", csv(syntheticRows.map { it to 0.02 }))

        val (code, out) = run(
            "ingest", "--ledger", File(dir, "ledger").path,
            "--results", File(dir, "run-1.csv").path,
            "--log", File(dir, "run-1.log").path,
            "--load", "1.2", "--cores", "16",
        )
        code shouldBe 1
        out shouldContain "harness-sha"
    }

    @Test
    fun `render refuses a ledger whose units span two harness shas, naming both`(
        @TempDir dir: File,
    ) {
        startSyntheticLedger(File(dir, "ledger"))
        listOf("aaaa111", "aaaa111", "bbbb222").forEachIndexed { index, sha ->
            ingestUnitAt(
                dir, "run-${index + 1}", csv(syntheticRows.map { it to 0.02 }), sha,
            ).first shouldBe 0
        }

        val (code, out) = run(
            "render", "--ledger", File(dir, "ledger").path,
            "--derived-on", "2026-08-27", "--jmh-config", "x",
        )
        code shouldBe 1
        out shouldContain "REFUSED"
        out shouldContain "span 2 harness shas"
        out shouldContain "aaaa111"
        out shouldContain "bbbb222"
        out shouldContain "run-3"
    }

    @Test
    fun `the second harness sha is warned about at ingest`(@TempDir dir: File) {
        startSyntheticLedger(File(dir, "ledger"))
        ingestUnitAt(dir, "run-1", csv(syntheticRows.map { it to 0.02 }), "aaaa111")
            .second shouldNotContain "WARNING"

        val (code, out) = ingestUnitAt(
            dir, "run-2", csv(syntheticRows.map { it to 0.03 }), "bbbb222",
        )
        code shouldBe 0
        out shouldContain "WARNING"
        out shouldContain "REFUSED at render time"
    }

    @Test
    fun `render publishes the sha the units attest and refuses one that disagrees`(
        @TempDir dir: File,
    ) {
        startSyntheticLedger(File(dir, "ledger"))
        listOf(0.01, 0.02, 0.03).forEachIndexed { index, dispersion ->
            ingestUnitAt(
                dir, "run-${index + 1}", csv(syntheticRows.map { it to dispersion }), "aaaa111",
            ).first shouldBe 0
        }

        val (code, out) = run(
            "render", "--ledger", File(dir, "ledger").path,
            "--derived-on", "2026-08-27", "--jmh-config", "x",
        )
        code shouldBe 0
        out shouldContain "harnessCommitSha = \"aaaa111\""
        out shouldContain "Harness: aaaa111"

        // The unfixed tool published this unchecked. It is now the refusal.
        val (badCode, badOut) = run(
            "render", "--ledger", File(dir, "ledger").path,
            "--derived-on", "2026-08-27", "--harness-sha", "deadbeef", "--jmh-config", "x",
        )
        badCode shouldBe 1
        badOut shouldContain "REFUSED"
        badOut shouldContain "deadbeef"
        badOut shouldContain "aaaa111"
    }

    /**
     * `computenet-eo9m` at the operator's surface. [FloorDerivationLedger.renderWarnings]
     * is only useful if `render` actually prints its warning, and prints it ABOVE the
     * block the operator pastes — the same place the partial-attestation warning lands, so
     * an operator who has learned where warnings appear does not have to learn a second
     * place. That ordering is a property of this command's output and no ledger-level test
     * can see it.
     */
    @Test
    fun `render warns above the block when the published sha is attested by no unit`(
        @TempDir dir: File,
    ) {
        val ledger = startSyntheticLedger(File(dir, "ledger"))
        listOf(0.01, 0.02, 0.03).forEachIndexed { index, dispersion ->
            ingestUnitAt(
                dir, "run-${index + 1}", csv(syntheticRows.map { it to dispersion }), "aaaa111",
            ).first shouldBe 0
        }
        // Downgraded to the v1 shape: no unit records a sha, so the caller's
        // --harness-sha is the published provenance and nothing checks it.
        ledger.file.writeText(
            ledger.file.readLines().joinToString("\n") { line ->
                when {
                    line == "floor-derivation-ledger v2" -> "floor-derivation-ledger v1"
                    line.startsWith("unit ") -> line.substringBeforeLast('|')
                    else -> line
                }
            } + "\n"
        )

        val (code, out) = run(
            "render", "--ledger", File(dir, "ledger").path,
            "--derived-on", "2026-08-27", "--harness-sha", "deadbeef", "--jmh-config", "x",
        )

        // A warning, not a refusal — an in-flight v1 derivation still renders at exit 0.
        code shouldBe 0
        out shouldContain "WARNING: no unit"
        out shouldContain "deadbeef"
        (out.indexOf("WARNING: no unit") < out.indexOf("ClassNoiseFloor(")) shouldBe true
        (out.indexOf("WARNING: no unit") < out.indexOf("--- findings.md block ---")) shouldBe true
    }

    @Test
    fun `a single-day and a multi-day set render distinguishably`(@TempDir dir: File) {
        val sameDay = File(dir, "same")
        sameDay.mkdirs()
        startSyntheticLedger(File(sameDay, "ledger"))
        listOf("2026-08-27T09:00:00Z", "2026-08-27T13:00:00Z", "2026-08-27T18:00:00Z")
            .forEachIndexed { index, stamp ->
                ingestUnitAt(
                    sameDay, "run-${index + 1}",
                    csv(syntheticRows.map { it to 0.01 * (index + 1) }), "aaaa111", stamp,
                ).first shouldBe 0
            }

        val spread = File(dir, "spread")
        spread.mkdirs()
        startSyntheticLedger(File(spread, "ledger"))
        listOf("2026-08-20T09:00:00Z", "2026-08-23T09:00:00Z", "2026-08-26T09:00:00Z")
            .forEachIndexed { index, stamp ->
                ingestUnitAt(
                    spread, "run-${index + 1}",
                    csv(syntheticRows.map { it to 0.01 * (index + 1) }), "aaaa111", stamp,
                ).first shouldBe 0
            }

        val (sameCode, sameOut) = run(
            "render", "--ledger", File(sameDay, "ledger").path,
            "--derived-on", "2026-08-27", "--jmh-config", "x",
        )
        val (spreadCode, spreadOut) = run(
            "render", "--ledger", File(spread, "ledger").path,
            "--derived-on", "2026-08-26", "--jmh-config", "x",
        )
        sameCode shouldBe 0
        spreadCode shouldBe 0

        sameOut shouldContain "Gathering window: all 3 unit(s) measured on ONE UTC day"
        sameOut shouldNotContain "is NOT the span"

        spreadOut shouldContain "spread over 3 UTC calendar days"
        spreadOut shouldContain "2026-08-20T09:00:00Z"
        spreadOut shouldContain "2026-08-26T09:00:00Z"
        spreadOut shouldContain "is NOT the span"

        // The span rides INSIDE the block the operator pastes, not beside it — findings.md
        // is what every later reader consults.
        spreadOut.substringAfter("--- findings.md block ---") shouldContain "Gathering window:"
    }

    @Test
    fun `status reports the harness sha and the window alongside the measuring JVM`(
        @TempDir dir: File,
    ) {
        startSyntheticLedger(File(dir, "ledger"))
        ingestUnitAt(
            dir, "run-1", csv(syntheticRows.map { it to 0.02 }), "aaaa111",
            "2026-08-20T09:00:00Z",
        ).first shouldBe 0

        val (code, out) = run("status", "--ledger", File(dir, "ledger").path)
        code shouldBe 0
        out shouldContain "harness sha(s) seen: aaaa111"
        out shouldContain "Gathering window:"
    }

    @Test
    fun `render --existing refuses a class with no committed entry`() {
        val (code, out) = run("render", "--existing", "NoSuchBenchmark")
        code shouldBe 1
        out shouldContain "REFUSED"
        out shouldContain "no entry for 'NoSuchBenchmark'"
    }

    // -----------------------------------------------------------------------------------
    // NextPlanner — the batching arithmetic, against a canned estimate (no jar, no
    // reflection, no JMH annotation dependency — see this file's own header).
    // -----------------------------------------------------------------------------------

    @Test
    fun `next batches all outstanding rows of a method when they comfortably fit the window`(
        @TempDir dir: File,
    ) {
        val ledger = startSyntheticLedger(dir)
        val unit = NextPlanner.next(ledger) { _, _ -> 10.0 }
        unit!!.method shouldBe "alpha"
        unit.rows.size shouldBe 3
        unit.varyingParam shouldBe "scale"
        unit.varyingValues.toSet() shouldBe setOf("N1E3", "N1E4", "N1E5")
        unit.estimatedSeconds shouldBe 30.0
    }

    @Test
    fun `next includes a single row alone when its own estimate already exceeds the target`(
        @TempDir dir: File,
    ) {
        val ledger = startSyntheticLedger(dir)
        // Larger than UnitSizing.TARGET_UNIT_SECONDS (480.0) on its own: nothing shrinks
        // to make it fit, so the unit is exactly one row.
        val unit = NextPlanner.next(ledger) { _, _ -> 900.0 }
        unit!!.rows.size shouldBe 1
        unit.estimatedSeconds shouldBe 900.0
    }

    @Test
    fun `next returns null once the ledger is complete`(@TempDir dir: File) {
        val ledger = startSyntheticLedger(dir)
        listOf(0.01, 0.02, 0.03).forEachIndexed { index, dispersion ->
            ledger.ingest(
                UnitAttestation(
                    unitId = "u$index",
                    measuringJvm = jdk21,
                    gate = GateReading(oneMinuteLoad = 1.0, cores = 16, attestedThreshold = 4.0),
                    timestamp = "2026-08-27T09:00:00Z",
                    harnessSha = harnessSha,
                ),
                csv(syntheticRows.map { it to dispersion }),
            )
        }
        NextPlanner.next(ledger) { _, _ -> 10.0 } shouldBe null
    }
}
