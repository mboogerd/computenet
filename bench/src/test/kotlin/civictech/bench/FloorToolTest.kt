package civictech.bench

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
            "--load", "1.2", "--cores", "16",
        )
        ingestCode shouldBe 0
        ingestOut shouldContain "ingested unit 'run-1'"

        val (statusCode, statusOut) = run("status", "--ledger", ledgerDir.path)
        statusCode shouldBe 0
        statusOut shouldContain "1/3"
        statusOut shouldContain jdk21
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
            "--load", "1.2", "--cores", "16",
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
                "--load", "1.2", "--cores", "16",
            )
        }

        val (code, out) = run(
            "render", "--ledger", ledgerDir.path,
            "--derived-on", "2026-08-27",
            "--harness-sha", "abcdef012",
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
            "--load", "1.2", "--cores", "16",
        )

        val (code, out) = run(
            "render", "--ledger", ledgerDir.path,
            "--derived-on", "2026-08-27", "--harness-sha", "abc", "--jmh-config", "x",
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
                "--load", "1.2", "--cores", "16",
            )
        }

        val (code, out) = run(
            "render", "--ledger", ledgerDir.path,
            "--derived-on", "2026-08-27", "--harness-sha", "abc", "--jmh-config", "x",
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
                ),
                csv(syntheticRows.map { it to dispersion }),
            )
        }
        NextPlanner.next(ledger) { _, _ -> 10.0 } shouldBe null
    }
}
