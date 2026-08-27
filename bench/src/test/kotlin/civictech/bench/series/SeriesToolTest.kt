package civictech.bench.series

import civictech.bench.HarnessJarStamp
import civictech.bench.HostFactsUnknownException
import civictech.bench.MeasuringJvmUnknownException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Ingest and the command-line face.
 *
 * The refusals are the substance here. A series entry's environment is not decoration —
 * it is the fingerprint deciding which past runs a fresh one is comparable to — so a run
 * whose log cannot answer for its own JVM or host must produce no entries at all, rather
 * than entries carrying the ingesting machine's facts.
 */
class SeriesToolTest {

    @Test
    fun `a well-formed run ingests every row with the environment its log states`(@TempDir dir: File) {
        val results = write(dir, "run.csv", CSV)
        write(dir, "run.log", LOG)

        val entries = SeriesIngest.entriesFrom(
            results = results,
            runId = "2026-08-22T07-00-00Z",
            runTimestampUtc = "2026-08-22T07:00:00Z",
            hostState = HostState.QUIESCED,
            harnessCommitSha = "ec98411f",
        )

        assertEquals(2, entries.size)
        val first = entries.first()
        assertEquals("civictech.bench.micro.SmokeBenchmark.baseline", first.benchmark)
        assertEquals(4.321050323941347, first.value)
        assertEquals(0.004992364297944783, first.dispersion)
        assertEquals("ns/op", first.unit)
        assertEquals(mapOf("degree" to "16"), first.params)
        // From the LOG, not from this JVM: the test runs on whatever the Gradle daemon's
        // toolchain is, and the log names an M2 Pro with 10 cores.
        assertEquals("Apple M2 Pro", first.env.cpuModel)
        assertEquals(10, first.env.coreCount)
        // `OpenJDK 64-Bit Server VM`, not `Eclipse Adoptium`: this fixture log carries no
        // `# VM invoker:` line, so `MeasuringJvm.fromJmhLog` has no `release` file to read
        // an IMPLEMENTOR from and falls back to the banner's VM name — identifying the JVM
        // build without guessing a distributor. That fallback is the documented behaviour
        // and is asserted here rather than papered over with an invoker line, because a
        // real run log always has the invoker and this one is proving the fallback works.
        assertEquals("OpenJDK 64-Bit Server VM", first.env.jvmVendor)
        assertEquals("21.0.11", first.env.jvmVersion)
        assertEquals(5, first.env.forkCount)
        assertEquals("ec98411f", first.env.harnessCommitSha)
        // The two rows differ only in their @Param, so they are two measurements.
        assertEquals(
            setOf(mapOf("degree" to "16"), mapOf("degree" to "64")),
            entries.map { it.params }.toSet(),
        )
    }

    @Test
    fun `a missing run log is refused rather than filled in from this process`(@TempDir dir: File) {
        val results = write(dir, "run.csv", CSV)

        val failure = assertThrows<IllegalArgumentException> {
            SeriesIngest.entriesFrom(results, "run", "2026-08-22T07:00:00Z", HostState.QUIESCED, "sha")
        }
        assertTrue(failure.message!!.contains("run log not found"), failure.message)
    }

    @Test
    fun `a log with no host banner is refused — the sentinel's own failure mode`(@TempDir dir: File) {
        // This is exactly what SmokeBenchmark's log looked like before this change added
        // its announceHost() hook: a complete JMH banner, and no host facts anywhere.
        val results = write(dir, "run.csv", CSV)
        write(dir, "run.log", LOG.lines().filterNot { it.startsWith("# Host ") }.joinToString("\n"))

        assertThrows<HostFactsUnknownException> {
            SeriesIngest.entriesFrom(results, "run", "2026-08-22T07:00:00Z", HostState.QUIESCED, "sha")
        }
    }

    @Test
    fun `a log with no VM banner is refused`(@TempDir dir: File) {
        val results = write(dir, "run.csv", CSV)
        write(dir, "run.log", LOG.lines().filterNot { it.startsWith("# VM version") }.joinToString("\n"))

        assertThrows<MeasuringJvmUnknownException> {
            SeriesIngest.entriesFrom(results, "run", "2026-08-22T07:00:00Z", HostState.QUIESCED, "sha")
        }
    }

    @Test
    fun `compare against an empty series reports no band and writes nothing`(@TempDir dir: File) {
        val results = write(dir, "run.csv", CSV)
        write(dir, "run.log", LOG)
        val series = write(dir, "series.csv", SeriesCsv.headerLine() + "\n")

        val out = StringBuilder()
        val code = SeriesCli.run(cli("compare", results, series), out)

        assertEquals(0, code, out.toString())
        assertTrue(out.contains("2 without sufficient history"), out.toString())
        assertEquals(SeriesCsv.headerLine() + "\n", series.readText())
    }

    @Test
    fun `append writes the rows and compares against the state BEFORE the run`(@TempDir dir: File) {
        val results = write(dir, "run.csv", CSV)
        write(dir, "run.log", LOG)
        val series = write(dir, "series.csv", SeriesCsv.headerLine() + "\n")

        val out = StringBuilder()
        assertEquals(0, SeriesCli.run(cli("append", results, series), out), out.toString())

        assertTrue(out.contains("Appended 2 row(s)"), out.toString())
        assertEquals(2, SeriesCsv.parse(series.readText(), series.path).size)
        // The comparison in this very output saw an empty history, not its own rows.
        assertTrue(out.contains("2 without sufficient history"), out.toString())
    }

    @Test
    fun `a shared-host append says so, because those rows will not form a band`(@TempDir dir: File) {
        val results = write(dir, "run.csv", CSV)
        write(dir, "run.log", LOG)
        val series = write(dir, "series.csv", SeriesCsv.headerLine() + "\n")

        val out = StringBuilder()
        SeriesCli.run(cli("append", results, series, hostState = "shared"), out)

        assertTrue(out.contains("attested SHARED"), out.toString())
        assertTrue(out.contains("will NOT contribute to any tolerance band"), out.toString())
        assertTrue(SeriesCsv.parse(series.readText(), series.path).all { it.hostState == HostState.SHARED })
    }

    @Test
    fun `movement is reported, not signalled by a non-zero exit code`(@TempDir dir: File) {
        // The lane must not become a gate: this ticket's scope keeps benchmark execution
        // out of anything that gates, and an exit code is what a scheduler would wire to.
        val results = write(dir, "run.csv", CSV)
        write(dir, "run.log", LOG)
        val series = write(dir, "series.csv", SeriesCsv.headerLine() + "\n")

        // Seed three quiesced entries far from the fresh run's 4.32 ns/op.
        val seed = SeriesIngest.entriesFrom(results, "seed", "2026-08-21T07:00:00Z", HostState.QUIESCED, "sha")
            .filter { it.params == mapOf("degree" to "16") }
        SeriesCsv.append(
            series,
            listOf(
                seed.single().copy(runId = "s1", value = 9.0, dispersion = 0.01),
                seed.single().copy(runId = "s2", value = 9.1, dispersion = 0.01),
                seed.single().copy(runId = "s3", value = 9.2, dispersion = 0.01),
            ),
        )

        val out = StringBuilder()
        val code = SeriesCli.run(cli("compare", results, series), out)

        assertEquals(0, code, out.toString())
        assertTrue(out.contains("1 moved"), out.toString())
        assertTrue(out.contains("MovedLower"), out.toString())
    }

    @Test
    fun `a refusal exits 1 with a REFUSED line naming the artifact`(@TempDir dir: File) {
        val results = write(dir, "run.csv", CSV) // no log beside it
        val series = write(dir, "series.csv", SeriesCsv.headerLine() + "\n")

        val out = StringBuilder()
        assertEquals(1, SeriesCli.run(cli("compare", results, series), out))
        assertTrue(out.startsWith("REFUSED:"), out.toString())
    }

    @Test
    fun `an unknown subcommand and a missing flag both print usage and exit 1`(@TempDir dir: File) {
        val out = StringBuilder()
        assertEquals(1, SeriesCli.run(arrayOf("record"), out))
        assertTrue(out.contains("unknown subcommand 'record'"), out.toString())

        val out2 = StringBuilder()
        assertEquals(1, SeriesCli.run(arrayOf("compare", "--results", "x.csv"), out2))
        // Which flag is named first is an ordering detail of the parser; that a required
        // flag is named at all, rather than defaulted, is the contract.
        assertTrue(out2.contains("missing required --"), out2.toString())
        assertTrue(out2.contains("benchSeries compare"), out2.toString())

        val out3 = StringBuilder()
        assertEquals(0, SeriesCli.run(arrayOf("--help"), out3))
        assertTrue(out3.contains("benchSeries compare"), out3.toString())
    }

    @Test
    fun `an unrecognised host-state is refused rather than defaulted`(@TempDir dir: File) {
        val results = write(dir, "run.csv", CSV)
        val series = write(dir, "series.csv", SeriesCsv.headerLine() + "\n")

        val out = StringBuilder()
        assertEquals(1, SeriesCli.run(cli("compare", results, series, hostState = "probably-idle"), out))
        assertTrue(out.contains("--host-state must be one of"), out.toString())
    }

    /**
     * `--jar` defaults to a jar stamped with the same sha as `--harness-sha`, so every
     * pre-existing case (none of which is about the jar-provenance check at all) passes
     * that check transparently and exercises whichever refusal or behaviour it actually
     * targets, unmasked by an unrelated jar mismatch.
     */
    // -----------------------------------------------------------------------------------
    // computenet-0ado — --jar's own build provenance, checked against --harness-sha.
    //
    // These three arms are what closed the acceptance clause that could not be reached
    // from run-series.test.sh's shell harness: that fixture's throwaway git repo has no
    // Gradle project and no Kotlin sources, so it cannot produce a real bench-jmh.jar or
    // invoke SeriesCli at all -- the refusal is only reachable through a real
    // `:bench:jmhJar` + `:bench:benchSeries` build, which the fixture is deliberately
    // isolated from. Landing the coverage HERE instead is stronger, not weaker: it
    // exercises SeriesCli.run directly (the actual refusal code, not a shell-level proxy
    // for it), the same way FloorToolTest.stampedJar already covers the identical check
    // on FloorTool.kt's runIngest (`computenet-7doz`).
    // -----------------------------------------------------------------------------------

    @Test
    fun `append refuses a jar with no stamped harness sha, naming the rebuild command`(@TempDir dir: File) {
        val results = write(dir, "run.csv", CSV)
        write(dir, "run.log", LOG)
        val series = write(dir, "series.csv", SeriesCsv.headerLine() + "\n")
        // No sha: a jar that predates the stamp, or was not produced by :bench:jmhJar.
        val jar = stampedJar(dir, sha = null)

        val out = StringBuilder()
        val code = SeriesCli.run(cli("append", results, series, jar = jar), out)

        assertEquals(1, code, out.toString())
        assertTrue(out.contains("REFUSED"), out.toString())
        assertTrue(out.contains("Harness-Commit-Sha"), out.toString())
        assertTrue(out.contains("./gradlew :bench:jmhJar"), out.toString())
        // Refused before anything was appended.
        assertEquals(SeriesCsv.headerLine() + "\n", series.readText())
    }

    @Test
    fun `append refuses a jar whose stamped sha disagrees with the working tree, the stale-jar case`(
        @TempDir dir: File,
    ) {
        val results = write(dir, "run.csv", CSV)
        write(dir, "run.log", LOG)
        val series = write(dir, "series.csv", SeriesCsv.headerLine() + "\n")
        // The jar was built at 'stale111' but the working tree HEAD read before this run
        // (--harness-sha) is 'fresh222' -- the jar was not rebuilt after the checkout
        // changed (e.g. :bench:jmhJar reported UP-TO-DATE and kept its old stamp).
        val jar = stampedJar(dir, "stale111")

        val out = StringBuilder()
        val code = SeriesCli.run(cli("append", results, series, harnessSha = "fresh222", jar = jar), out)

        assertEquals(1, code, out.toString())
        assertTrue(out.contains("REFUSED"), out.toString())
        assertTrue(out.contains("stale111"), out.toString())
        assertTrue(out.contains("fresh222"), out.toString())
        assertTrue(out.contains("./gradlew :bench:jmhJar"), out.toString())
        assertEquals(SeriesCsv.headerLine() + "\n", series.readText())
    }

    @Test
    fun `append accepts a jar whose stamp agrees with the working tree, and records the STAMPED sha`(
        @TempDir dir: File,
    ) {
        val results = write(dir, "run.csv", CSV)
        write(dir, "run.log", LOG)
        val series = write(dir, "series.csv", SeriesCsv.headerLine() + "\n")
        // Same jar as the stale-jar case above, but paired with the harness-sha it
        // actually agrees with -- the discriminating half: a stamp mismatch is refused,
        // and the identical jar with a matching --harness-sha is accepted.
        val jar = stampedJar(dir, "stale111")

        val out = StringBuilder()
        val code = SeriesCli.run(cli("append", results, series, harnessSha = "stale111", jar = jar), out)

        assertEquals(0, code, out.toString())
        assertTrue(out.contains("Appended 2 row(s)"), out.toString())
        val recorded = SeriesCsv.parse(series.readText(), series.path)
        assertEquals(2, recorded.size)
        // The jar's OWN stamp is what lands in the row -- not merely "the value that was
        // also passed as --harness-sha", which this case cannot tell apart from the
        // stamp since a passing run requires the two to already agree. What DOES
        // distinguish them is the refusal above: a caller that mixed up which value to
        // pass (working-tree sha as --jar's stamp, say) is refused there, so only a jar
        // whose OWN manifest attribute equals what gets recorded here ever reaches this
        // assertion at all.
        assertTrue(recorded.all { it.env.harnessCommitSha == "stale111" }, recorded.toString())
    }

    /**
     * The bypass itself, guarded (`computenet-0ado`). The first cut of this change made
     * `--jar` optional, which left the unattested path reachable by omitting one flag —
     * the stamp check never runs and `--harness-sha` is recorded verbatim, which is the
     * whole defect. Requiring the flag is what closes it, so the requirement needs its
     * own arm: every other case here supplies `--jar`, so nothing above notices if the
     * flag goes back to being defaulted.
     */
    @Test
    fun `append refuses when --jar is omitted altogether, so the unattested path is unreachable`(
        @TempDir dir: File,
    ) {
        val results = write(dir, "run.csv", CSV)
        write(dir, "run.log", LOG)
        val series = write(dir, "series.csv", SeriesCsv.headerLine() + "\n")

        val out = StringBuilder()
        val code = SeriesCli.run(
            cli("append", results, series).filterNot { it == "--jar" || it.endsWith("bench-jmh.jar") }
                .toTypedArray(),
            out,
        )

        assertEquals(1, code, out.toString())
        assertTrue(out.contains("missing required --jar"), out.toString())
        assertEquals(SeriesCsv.headerLine() + "\n", series.readText())
    }

    private fun cli(
        command: String,
        results: File,
        series: File,
        hostState: String = "quiesced",
        harnessSha: String = "ec98411f",
        jar: File = stampedJar(results.parentFile, harnessSha),
    ): Array<String> = arrayOf(
        command,
        "--results", results.path,
        "--series", series.path,
        "--run-id", "run",
        "--timestamp", "2026-08-22T07:00:00Z",
        "--host-state", hostState,
        "--harness-sha", harnessSha,
        "--jar", jar.path,
    )

    private fun write(dir: File, name: String, text: String): File =
        File(dir, name).apply { writeText(text) }

    /**
     * A minimal jar carrying [sha] as its `Harness-Commit-Sha` manifest attribute
     * (`computenet-0ado`) — the fixture standing in for `bench/build.gradle.kts`'s
     * `jmhJar` task, which this module's `test` task never runs. `sha == null` produces a
     * jar with no such attribute, for the "unstamped jar" refusal. Mirrors
     * `FloorToolTest.stampedJar` (`computenet-7doz`), the same fixture for the sibling
     * tool's identical check.
     */
    private fun stampedJar(dir: File, sha: String?, name: String = "bench-jmh.jar"): File {
        val jarFile = File(dir, name)
        val manifest = Manifest()
        manifest.mainAttributes.putValue("Manifest-Version", "1.0")
        if (sha != null) {
            manifest.mainAttributes.putValue(HarnessJarStamp.MANIFEST_ATTRIBUTE, sha)
        }
        FileOutputStream(jarFile).use { fos ->
            JarOutputStream(fos, manifest).use { }
        }
        return jarFile
    }

    private companion object {
        /**
         * A two-row JMH `-rf csv` results file. The `Score Error (99.9%)` header is what
         * `ThroughputReport.parseCsv` verifies actually says 99.9%, because
         * `BenchResult.dispersion` is defined as that statistic.
         */
        val CSV = """
            "Benchmark","Mode","Threads","Samples","Score","Score Error (99.9%)","Unit","Param: degree"
            "civictech.bench.micro.SmokeBenchmark.baseline","avgt",1,25,4.321050323941347,0.004992364297944783,"ns/op","16"
            "civictech.bench.micro.SmokeBenchmark.baseline","avgt",1,25,8.100000000000000,0.010000000000000000,"ns/op","64"
        """.trimIndent() + "\n"

        /** A JMH banner carrying every line the three environment readers require. */
        val LOG = """
            # JMH version: 1.37
            # VM version: JDK 21.0.11, OpenJDK 64-Bit Server VM, 21.0.11+10-LTS
            # VM options: <none>
            # Blackhole mode: compiler (auto-detected)
            # Warmup: 5 iterations, 10 s each
            # Measurement: 5 iterations, 10 s each
            # Timeout: 10 min per iteration
            # Threads: 1 thread, will synchronize iterations
            # Benchmark mode: Average time, time/op
            # Benchmark: civictech.bench.micro.SmokeBenchmark.baseline
            # Fork: 1 of 5
            # Host CPU model: Apple M2 Pro
            # Host core count: 10
            # Host OS: Mac OS X 26.6.1
            # Fork: 5 of 5
        """.trimIndent() + "\n"
    }
}
