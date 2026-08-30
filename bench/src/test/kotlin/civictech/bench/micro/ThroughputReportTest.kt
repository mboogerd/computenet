package civictech.bench.micro

import civictech.bench.BenchResult
import civictech.bench.ComparisonClaim
import civictech.bench.Drive
import civictech.bench.Findings
import civictech.bench.FindingsRefusalException
import civictech.bench.FindingsTable
import civictech.bench.HostFacts
import civictech.bench.HostFactsUnknownException
import civictech.bench.MeasuringJvm
import civictech.bench.MeasuringJvmUnknownException
import civictech.bench.NOISE_FLOOR
import civictech.bench.Reportability
import civictech.bench.RunEnvironment
import civictech.bench.RunKnobs
import civictech.bench.RunKnobsUnknownException
import civictech.bench.TriggerClaim
import civictech.bench.classify
import civictech.bench.hasClassFloor
import civictech.bench.noiseFloorFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.management.ManagementFactory

/**
 * Fixture-driven tests for [ThroughputReport] — no JMH run, no graph, sub-second.
 *
 * The CSV below is JMH 1.37's own `-rf csv` dialect: a quoted header naming the primary
 * metric columns and one `Param: <name>` column per `@Param`, then one row per
 * benchmark x parameter combination. It is held here as text on purpose — the whole
 * point of the renderer is that it is exercisable without paying for a measurement, so
 * a test that needed a real results file would defeat it.
 */
class ThroughputReportTest {

    private val env = RunEnvironment(
        jvmVendor = "Eclipse Adoptium",
        jvmVersion = "21.0.11+10-LTS",
        heapSettings = "maxHeapBytes=4294967296",
        cpuModel = "Apple M2 Pro",
        coreCount = 10,
        os = "Mac OS X 26.6.1",
        jmhMode = "Throughput",
        forkCount = 2,
        warmupIterations = 5,
        measurementIterations = 10,
        harnessCommitSha = "b861114d",
    )

    private val header =
        """"Benchmark","Mode","Threads","Samples","Score","Score Error (99.9%)","Unit",""" +
            """"Param: direction","Param: subject""""

    /** Two drives x two directions, every row comfortably inside [NOISE_FLOOR]. */
    private val quietCsv = listOf(
        header,
        """"civictech.bench.micro.OperatorThroughputBenchmark.sim","thrpt",1,20,120000.0,240.0,"ops/s","INSERT","FILTER"""",
        """"civictech.bench.micro.OperatorThroughputBenchmark.sim","thrpt",1,20,90000.0,180.0,"ops/s","RETRACT","FILTER"""",
        """"civictech.bench.micro.OperatorThroughputBenchmark.real","thrpt",1,20,45000.0,90.0,"ops/s","INSERT","FILTER"""",
        """"civictech.bench.micro.OperatorThroughputBenchmark.real","thrpt",1,20,30000.0,60.0,"ops/s","RETRACT","FILTER"""",
    ).joinToString("\n")

    @Test
    fun `parses JMH csv columns by name, including params`() {
        val rows = ThroughputReport.parseCsv(quietCsv)
        assertEquals(4, rows.size)
        val first = rows.first()
        assertEquals("civictech.bench.micro.OperatorThroughputBenchmark.sim", first.benchmark)
        assertEquals("sim", first.method)
        assertEquals("thrpt", first.mode)
        assertEquals(120000.0, first.score)
        assertEquals(240.0, first.scoreError)
        assertEquals("ops/s", first.unit)
        assertEquals(mapOf("direction" to "INSERT", "subject" to "FILTER"), first.params)
    }

    @Test
    fun `refuses a results file whose error column is not at 99 point 9 percent confidence`() {
        val csv = quietCsv.replace("Score Error (99.9%)", "Score Error (95.0%)")
        val failure = assertThrows(ThroughputReportException::class.java) {
            ThroughputReport.parseCsv(csv)
        }
        assertTrue(failure.message!!.contains("99.9"), failure.message)
    }

    @Test
    fun `refuses a row whose error is NaN because the run had too few samples`() {
        // Observed 2026-08-18 from a real `-f 1 -wi 1 -i 1` smoke run (Samples=1): JMH
        // writes the literal NaN. Confirmed against JMH 1.37's own
        // AbstractStatistics.getMeanErrorAt (org.openjdk.jmh:jmh-core:1.37, the pinned
        // dependency): it returns NaN for every n <= 2, and only becomes finite once
        // n > 2 (i.e. at three samples), so the remedy this refusal states must name
        // three, not two.
        val csv = listOf(
            header,
            """"civictech.bench.micro.OperatorThroughputBenchmark.sim","thrpt",1,1,469734.975841,NaN,"ops/s",INSERT,FILTER"""",
        ).joinToString("\n")
        val failure = assertThrows(ThroughputReportException::class.java) {
            ThroughputReport.parseCsv(csv)
        }
        assertTrue(failure.message!!.contains("too few"), failure.message)
        assertTrue(failure.message!!.contains("at least three measurement"), failure.message)
    }

    @Test
    fun `still refuses at exactly two measurement samples, and does not advise re-running at two`() {
        // The boundary the earlier (wrong) advice missed: JMH's ListStatistics also
        // writes NaN at Samples=2, not only below it. A session that followed "re-run
        // with at least two" would land back on this identical refusal.
        val csv = listOf(
            header,
            """"civictech.bench.micro.OperatorThroughputBenchmark.sim","thrpt",1,2,469734.975841,NaN,"ops/s",INSERT,FILTER"""",
        ).joinToString("\n")
        val failure = assertThrows(ThroughputReportException::class.java) {
            ThroughputReport.parseCsv(csv)
        }
        assertTrue(failure.message!!.contains("too few"), failure.message)
        assertTrue(failure.message!!.contains("at least three measurement"), failure.message)
        assertFalse(failure.message!!.contains("at least two measurement"), failure.message)
    }

    @Test
    fun `a row with a finite error at three measurement samples parses cleanly`() {
        // Three is the fewest sample count JMH's own statistics resolve to a finite
        // error at (getN() > 2 in AbstractStatistics.getMeanErrorAt) — the row the
        // corrected remedy actually leads to.
        val csv = listOf(
            header,
            """"civictech.bench.micro.OperatorThroughputBenchmark.sim","thrpt",1,3,469734.975841,58421.87,"ops/s",INSERT,FILTER"""",
        ).joinToString("\n")
        val rows = ThroughputReport.parseCsv(csv)
        assertEquals(1, rows.size)
        assertTrue(rows.single().scoreError.isFinite())
    }

    @Test
    fun `refuses a benchmark name that does not state exactly one drive`() {
        val ambiguous = quietCsv.replace(
            "OperatorThroughputBenchmark.sim",
            "OperatorThroughputBenchmark.simVersusReal",
        )
        val failure = assertThrows(ThroughputReportException::class.java) {
            ThroughputReport.toResults(ThroughputReport.parseCsv(ambiguous), env)
        }
        assertTrue(failure.message!!.contains("does not name exactly one drive"), failure.message)

        val silent = quietCsv.replace("OperatorThroughputBenchmark.sim", "OperatorThroughputBenchmark.apply")
        assertThrows(ThroughputReportException::class.java) {
            ThroughputReport.toResults(ThroughputReport.parseCsv(silent), env)
        }
    }

    @Test
    fun `refuses a row that cannot be labelled by subject and direction`() {
        val csv = quietCsv.replace("\"Param: subject\"", "\"Param: unrelated\"")
        val failure = assertThrows(ThroughputReportException::class.java) {
            ThroughputReport.toResults(ThroughputReport.parseCsv(csv), env)
        }
        assertTrue(failure.message!!.contains("cannot be labelled"), failure.message)
    }

    @Test
    fun `groups by drive into one entry per drive, labelled subject plus direction`() {
        val report = ThroughputReport.render(quietCsv, env, "2026-08-18", "operator throughput")
        assertEquals(listOf(Drive.SIM, Drive.REAL), report.perDrive.map { it.drive })
        assertTrue(report.dispersions.none { it.aboveHarnessSanityBound })

        val sim = report.perDrive.single { it.drive == Drive.SIM }.entry
        assertTrue(sim.contains("drive=SIM"), sim)
        assertFalse(sim.contains("drive=REAL"), sim)
        assertTrue(sim.contains("| FILTER insert | 120000.0 ± 240.0 ops/s |"), sim)
        assertTrue(sim.contains("| FILTER retract | 90000.0 ± 180.0 ops/s |"), sim)
        // The REAL rows are in the other entry, never in this one.
        assertFalse(sim.contains("45000.0"), sim)

        val real = report.perDrive.single { it.drive == Drive.REAL }.entry
        assertTrue(real.contains("drive=REAL"), real)
        assertTrue(real.contains("| FILTER insert | 45000.0 ± 90.0 ops/s |"), real)

        // Findings' own environment and trigger lines, rendered by F3, not re-derived here.
        assertTrue(sim.contains("Harness: b861114d"), sim)
        assertTrue(sim.contains("forks=2 warmup=5 iters=10"), sim)
        assertTrue(sim.contains("MARKED INCOMPLETE"), sim)

        val text = report.text()
        assertTrue(text.contains("Row dispersion (drive=SIM;"), text)
        assertTrue(text.contains("- FILTER insert (drive=SIM): 120000.0 ± 240.0 ops/s"), text)
    }

    @Test
    fun `a mixed-drive table is refused by F3 itself, not by this renderer`() {
        val results = ThroughputReport.toResults(ThroughputReport.parseCsv(quietCsv), env)
        assertEquals(setOf(Drive.SIM, Drive.REAL), results.map { it.result.drive }.toSet())

        // The renderer never builds this table — it groups by drive first. Building it by
        // hand is refused by FindingsTable's constructor, which is where the refusal
        // lives; nothing in ThroughputReport re-implements or relaxes it.
        val failure = assertThrows(IllegalArgumentException::class.java) {
            FindingsTable(results.map { it.result }, results.map { it.label })
        }
        assertTrue(
            failure.message!!.contains("all results to share one Drive"),
            failure.message,
        )
    }

    /** One SIM row far too dispersed to report; its sibling row is fine. */
    private val noisyCsv = listOf(
        header,
        """"civictech.bench.micro.OperatorThroughputBenchmark.sim","thrpt",1,20,120000.0,240.0,"ops/s","INSERT","FILTER"""",
        """"civictech.bench.micro.OperatorThroughputBenchmark.sim","thrpt",1,20,90000.0,27000.0,"ops/s","RETRACT","GROUP_BY_MIN"""",
    ).joinToString("\n")

    @Test
    fun `a row above the harness sanity bound is rendered with its error bar, not excluded`() {
        val report = ThroughputReport.render(noisyCsv, env, "2026-08-18", "operator throughput")
        val entry = report.perDrive.single().entry

        // computenet-785b: the row is IN the table, with its own error bar attached, so
        // the reader can discount it. Before 2026-08-22 it was dropped and named in an
        // omission list, which on hosted-graph sweeps emptied the table entirely.
        assertTrue(entry.contains("| FILTER insert | 120000.0 ± 240.0 ops/s |"), entry)
        assertTrue(entry.contains("| GROUP_BY_MIN retract | 90000.0 ± 27000.0 ops/s |"), entry)

        // Its dispersion is stated beside the table, flagged against the class's own
        // floor — informational, and gating nothing.
        val notes = report.dispersions
        assertEquals(2, notes.size)
        val noisy = notes.single { it.label == "GROUP_BY_MIN retract" }
        assertEquals(Drive.SIM, noisy.drive)
        assertTrue(noisy.aboveHarnessSanityBound)
        assertFalse(notes.single { it.label == "FILTER insert" }.aboveHarnessSanityBound)
        val text = report.text()
        assertTrue(text.contains("the row is reported"), text)

        // computenet-ahn0 wrote this half as a tripwire: the bound the sentence names is
        // resolved, not hard-wired, and it said NOISE_FLOOR only because
        // `OperatorThroughputBenchmark` had no derived class floor. `computenet-x9e.17`
        // derived one on 2026-08-28 (0.103, from three whole-class quiesced runs), so the
        // tripwire has now fired and is UPDATED to the new resolution rather than deleted —
        // which is exactly what it was written to force. The sentence names the row's own
        // @Benchmark METHOD's floor (`computenet-x9e.18` moved the grain from the class to
        // the method, and re-derived `sim`'s floor to the same 0.103 the retired class
        // floor carried, because `sim` held the row that set it) and no longer mentions the
        // global bound at all.
        assertEquals("sim", noisy.benchmarkMethod)
        assertTrue(hasClassFloor("OperatorThroughputBenchmark", "sim"))
        assertEquals(0.103, noiseFloorFor("OperatorThroughputBenchmark", "sim"))
        assertEquals(noiseFloorFor("OperatorThroughputBenchmark", "sim"), noisy.floor)
        assertTrue(
            text.contains("above the OperatorThroughputBenchmark.sim method floor 0.103"),
            text,
        )
        assertFalse(text.contains("NOISE_FLOOR"), text)

        // The row is still flagged, and it has to be for the rest of this test to mean
        // anything: 27000/90000 = 0.30, comfortably above the derived 0.103 as it was
        // above the global 0.005. What changed is which bound the reader is pointed at.
        assertTrue(
            noisy.result.relativeDispersion > noiseFloorFor("OperatorThroughputBenchmark", "sim")
        )
    }

    @Test
    fun `F3 renders the dispersed row alone but refuses a comparison drawn from it`() {
        val results = ThroughputReport.toResults(ThroughputReport.parseCsv(noisyCsv), env)
        val noisy = results.single { it.label.startsWith("GROUP_BY_MIN") }.result
        assertEquals(Reportability.Unreportable, classify(noisy))

        // Standalone: reportable, because the entry states its error bar.
        val entry = Findings.entry(
            date = "2026-08-18",
            subject = "operator throughput",
            results = FindingsTable(listOf(noisy), listOf("GROUP_BY_MIN retract")),
        )
        assertTrue(entry.contains("| GROUP_BY_MIN retract | 90000.0 ± 27000.0 ops/s |"), entry)

        // Compared with a sibling 10000 ops/s away: refused, because that effect is
        // well inside the two rows' combined 27000 + 27000 error bars.
        val sibling = noisy.copy(value = 100000.0)
        val failure = assertThrows(FindingsRefusalException::class.java) {
            Findings.entry(
                date = "2026-08-18",
                subject = "operator throughput",
                results = FindingsTable(
                    listOf(sibling, noisy),
                    listOf("GROUP_BY_MIN insert", "GROUP_BY_MIN retract"),
                ),
                comparisons = listOf(
                    ComparisonClaim(
                        "GROUP_BY_MIN insert",
                        "GROUP_BY_MIN retract",
                        "GROUP_BY_MIN inserts outrun its retracts",
                    )
                ),
            )
        }
        assertTrue(
            failure.message!!.contains("does not exceed the combined 99.9% error bars"),
            failure.message,
        )
    }

    @Test
    fun `a drive whose every row is above the sanity bound still renders its table`() {
        val csv = listOf(
            header,
            """"civictech.bench.micro.OperatorThroughputBenchmark.real","thrpt",1,20,90000.0,27000.0,"ops/s","INSERT","QUORUM"""",
        ).joinToString("\n")
        val report = ThroughputReport.render(csv, env, "2026-08-18", "operator throughput")
        val driveReport = report.perDrive.single()
        assertTrue(driveReport.entry.contains("| QUORUM insert | 90000.0 ± 27000.0 ops/s |"))
        assertEquals(1, driveReport.dispersions.size)
        assertTrue(driveReport.dispersions.single().aboveHarnessSanityBound)
        val text = report.text()
        assertFalse(text.contains("no entry for drive=REAL"), text)
        assertTrue(text.contains("QUORUM insert"), text)
    }

    @Test
    fun `a comparison whose effect clears the combined error bars is rendered`() {
        val report = ThroughputReport.render(
            quietCsv,
            env,
            "2026-08-18",
            "operator throughput",
            comparisons = listOf(
                ComparisonClaim(
                    "FILTER insert",
                    "FILTER retract",
                    "insert outruns retract on this graph",
                )
            ),
        )
        val sim = report.perDrive.single { it.drive == Drive.SIM }.entry
        // 120000 - 90000 = 30000 ops/s of effect against 240 + 180 = 420 of combined bar.
        assertTrue(sim.contains("Comparisons (effect vs combined error bars):"), sim)
        assertTrue(
            sim.contains("|Δ| = 30000.0 ops/s > combined 99.9% error 420.0 ops/s"),
            sim,
        )
        assertTrue(sim.contains("insert outruns retract on this graph"), sim)
        // Both drives carry both labels in this fixture, so the claim is drawn once per
        // drive, from that drive's own rows — never across the two.
        val real = report.perDrive.single { it.drive == Drive.REAL }.entry
        assertTrue(
            real.contains("|Δ| = 15000.0 ops/s > combined 99.9% error 150.0 ops/s"),
            real,
        )
    }

    @Test
    fun `a comparison naming rows of two different drives is refused`() {
        // SIM carries FILTER only, REAL carries GROUP_BY_MIN only, so this claim's two
        // rows live in different entries.
        val csv = listOf(
            header,
            """"civictech.bench.micro.OperatorThroughputBenchmark.sim","thrpt",1,20,120000.0,240.0,"ops/s","INSERT","FILTER"""",
            """"civictech.bench.micro.OperatorThroughputBenchmark.real","thrpt",1,20,45000.0,90.0,"ops/s","INSERT","GROUP_BY_MIN"""",
        ).joinToString("\n")
        val failure = assertThrows(ThroughputReportException::class.java) {
            ThroughputReport.render(
                csv,
                env,
                "2026-08-18",
                "operator throughput",
                comparisons = listOf(
                    ComparisonClaim("FILTER insert", "GROUP_BY_MIN insert", "SIM outruns REAL"),
                ),
            )
        }
        assertTrue(
            failure.message!!.contains("no single drive's table carries"),
            failure.message,
        )
    }

    @Test
    fun `a missing date is refused by F3's writer through the renderer`() {
        assertThrows(FindingsRefusalException::class.java) {
            ThroughputReport.render(quietCsv, env, "", "operator throughput")
        }
        assertThrows(FindingsRefusalException::class.java) {
            ThroughputReport.render(quietCsv, env, "2026-08-18", "")
        }
    }

    @Test
    fun `a malformed trigger claim is refused by F3's writer through the renderer`() {
        val failure = assertThrows(FindingsRefusalException::class.java) {
            ThroughputReport.render(
                quietCsv,
                env,
                "2026-08-18",
                "operator throughput",
                TriggerClaim.Cited("G-21 phase 3", "FIRES and RETIRES at once"),
            )
        }
        assertTrue(failure.message!!.contains("exactly one of FIRES"), failure.message)
    }

    @Test
    fun `a well-formed trigger claim reaches every drive's entry`() {
        val report = ThroughputReport.render(
            quietCsv,
            env,
            "2026-08-18",
            "operator throughput",
            TriggerClaim.Cited("G-21 phase 3", "INCONCLUSIVE: the sweep does not discriminate."),
        )
        assertTrue(report.perDrive.all { it.entry.contains("Trigger: G-21 phase 3") })
    }

    // ---------------------------------------------------------------------------------
    // computenet-hqid: the rendered environment must describe the JVM that PRODUCED the
    // measurements, and the renderer must refuse rather than substitute its own.
    //
    // What the defect looked like, measured on this branch's base commit (bedef35d)
    // before the fix. The retained `real-throughput.csv` of the REAL-drive sweep — a file
    // produced by Homebrew JDK 26.0.1, whose own JMH banner reads "# VM version: JDK
    // 26.0.1, OpenJDK 64-Bit Server VM, 26.0.1" and "# VM options: <none>" — rendered
    // through the documented command as:
    //
    //   Harness: UNFIXED1 · JVM Eclipse Adoptium/21.0.11 · heap -Xmx2g · Apple M2 Pro, ...
    //
    // That is the Gradle :bench:test JVM (jvmToolchain(21), forked -Xmx2g) doing the
    // rendering, five major versions and one heap flag away from the JVM that measured.
    // The tests below fail if that substitution returns: the first because rendering
    // without a run log would succeed again, the fifth because the entry would carry the
    // running JVM's own vendor, version and heap instead of the recorded ones.
    // ---------------------------------------------------------------------------------

    /**
     * A JMH banner as JMH 1.37 writes it, over the JVM facts under test.
     *
     * `invoker` is a fixture-supplied path — a fabricated JDK inside a `@TempDir`, or a
     * path that does not exist — so that what these tests assert is decided by the
     * fixture and not by whichever JDKs happen to be installed on the machine running
     * the suite.
     */
    private fun banner(
        version: String,
        vmName: String = "OpenJDK 64-Bit Server VM",
        invoker: String? = null,
        options: String = "<none>",
        knobs: String = knobBanner(),
        host: String = hostBanner(),
    ): String = buildString {
        appendLine("# JMH version: 1.37")
        appendLine("# VM version: JDK $version, $vmName, $version")
        if (invoker != null) appendLine("# VM invoker: $invoker")
        appendLine("# VM options: $options")
        append(knobs)
        append(host)
    }

    /**
     * The host-facts half of a run log — printed by
     * `OperatorThroughputBenchmark.GraphState.announceHost`'s `@Setup(Level.Trial)` hook
     * from inside the measuring fork (`[BEN1-23]`, computenet-yhbd).
     *
     * Defaults mirror the `env` fixture above, so the JVM/knob-focused tests stay
     * unaffected by this addition. They are NOT a host that differs from the machine
     * running the suite — on the development host they are exactly it (`Apple M2 Pro`,
     * 10 cores, measured 2026-08-19) — so do not build a recorded-vs-running test on the
     * defaults. The computenet-yhbd tests below state their own hosts: the refusals vary
     * [cpuModel], and the positive test names a deliberately unlike host and asserts that
     * it still differs from the running one.
     */
    private fun hostBanner(
        cpuModel: String? = "Apple M2 Pro",
        coreCount: Int? = 10,
        os: String? = "Mac OS X 26.6.1",
    ): String = buildString {
        if (cpuModel != null) appendLine("${HostFacts.CPU_MODEL_PREFIX} $cpuModel")
        if (coreCount != null) appendLine("${HostFacts.CORE_COUNT_PREFIX} $coreCount")
        if (os != null) appendLine("${HostFacts.OS_PREFIX} $os")
    }

    /**
     * The configuration half of JMH 1.37's banner — the lines it prints from the
     * parameters it RESOLVED, after any `-f`/`-wi`/`-i` override.
     *
     * The defaults deliberately state the values `OperatorThroughputBenchmark`'s
     * annotations declare, so that a test which changes nothing describes a sweep run at
     * the annotation config; the computenet-x9e.8 tests below change them, which is the
     * whole point of the fixture being parameterised.
     *
     * `# Fork:` is one line per fork, as JMH prints it, so the fixture states the run's
     * fork total the way a real log does rather than the way the parser finds convenient.
     */
    private fun knobBanner(
        mode: String = "${ThroughputReport.JMH_MODE}, ops/time",
        warmupIterations: Int? = ThroughputReport.WARMUP_ITERATIONS,
        measurementIterations: Int? = ThroughputReport.MEASUREMENT_ITERATIONS,
        forks: Int? = ThroughputReport.FORKS,
        forkLine: String? = null,
    ): String = buildString {
        val seconds = ThroughputReport.ITERATION_SECONDS
        appendLine(
            "# Warmup: " + (warmupIterations?.let { "$it iterations, $seconds s each" }
                ?: RunKnobs.NONE)
        )
        appendLine(
            "# Measurement: " + (measurementIterations?.let { "$it iterations, $seconds s each" }
                ?: RunKnobs.NONE)
        )
        appendLine("# Timeout: 10 min per iteration")
        appendLine("# Threads: 1 thread, will synchronize iterations")
        appendLine("# Benchmark mode: $mode")
        appendLine("# Benchmark: civictech.bench.micro.OperatorThroughputBenchmark.simApplyDelta")
        appendLine("# Parameters: (direction = INSERT, subject = fold)")
        when {
            forkLine != null -> appendLine("# Fork: $forkLine")
            forks != null -> (1..forks).forEach { appendLine("# Fork: $it of $forks") }
        }
    }

    /** A directory shaped like a JDK installation: `bin/java` plus a `release` file. */
    private fun fakeJdk(root: File, implementor: String, version: String): File {
        val home = File(root, "fake-jdk")
        File(home, "bin").mkdirs()
        File(home, "bin/java").writeText("#!/bin/sh\n")
        File(home, "release").writeText(
            """
            IMPLEMENTOR="$implementor"
            JAVA_VERSION="$version"
            OS_ARCH="aarch64"
            """.trimIndent()
        )
        return File(home, "bin/java")
    }

    /** Writes `throughput.csv`, and its `throughput.log` when [log] is non-null. */
    private fun runArtifacts(dir: File, csv: String, log: String?): File {
        val results = File(dir, "throughput.csv").apply { writeText(csv) }
        if (log != null) File(dir, "throughput.log").writeText(log)
        return results
    }

    @Test
    fun `refuses to render a results file with no run log beside it`(@TempDir dir: File) {
        val results = runArtifacts(dir, quietCsv, log = null)

        val failure = assertThrows(MeasuringJvmUnknownException::class.java) {
            ThroughputReport.renderRun(results, "b861114d", "2026-08-18", "operator throughput")
        }
        // Names the fact it could not establish, and where it looked for it.
        assertTrue(failure.message!!.contains("measuring JVM"), failure.message)
        assertTrue(
            failure.message!!.contains(ThroughputReport.runLogFor(results).absolutePath),
            failure.message,
        )
    }

    @Test
    fun `refuses a run log that carries no JMH banner`(@TempDir dir: File) {
        val results = runArtifacts(
            dir,
            quietCsv,
            log = "Exception in thread \"main\" java.lang.NoClassDefFoundError\n",
        )

        val failure = assertThrows(MeasuringJvmUnknownException::class.java) {
            ThroughputReport.renderRun(results, "b861114d", "2026-08-18", "operator throughput")
        }
        assertTrue(failure.message!!.contains(MeasuringJvm.VM_VERSION_PREFIX), failure.message)
    }

    @Test
    fun `refuses a run log that states the JVM but not the options it ran under`(
        @TempDir dir: File,
    ) {
        // Heap is one of the three facts [BEN1-23] requires, and the one BOTH shipped
        // entries got wrong. A log that does not state it is refused, not defaulted.
        val log = banner("21.0.11").lineSequence()
            .filterNot { it.startsWith(MeasuringJvm.VM_OPTIONS_PREFIX) }
            .joinToString("\n")
        val results = runArtifacts(dir, quietCsv, log = log)

        val failure = assertThrows(MeasuringJvmUnknownException::class.java) {
            ThroughputReport.renderRun(results, "b861114d", "2026-08-18", "operator throughput")
        }
        assertTrue(failure.message!!.contains(MeasuringJvm.VM_OPTIONS_PREFIX), failure.message)
    }

    @Test
    fun `refuses a run log that names two different JVMs`(@TempDir dir: File) {
        val results = runArtifacts(dir, quietCsv, log = banner("21.0.11") + banner("26.0.1"))

        val failure = assertThrows(MeasuringJvmUnknownException::class.java) {
            ThroughputReport.renderRun(results, "b861114d", "2026-08-18", "operator throughput")
        }
        assertTrue(failure.message!!.contains("not one run on one JVM"), failure.message)
    }

    @Test
    fun `renders the JVM the run recorded, never the one doing the rendering`(
        @TempDir dir: File,
    ) {
        // The recorded JVM is deliberately nothing like this test's own: a fabricated
        // vendor, a version this process cannot be running, and no heap flag at all.
        val invoker = fakeJdk(dir, implementor = "Acme JDK", version = "26.0.1")
        val results = runArtifacts(
            dir,
            quietCsv,
            log = banner("26.0.1", invoker = invoker.absolutePath, options = "<none>"),
        )

        val entry = ThroughputReport
            .renderRun(results, "b861114d", "2026-08-18", "operator throughput")
            .perDrive.first { it.drive == Drive.SIM }.entry

        // The RECORDED JVM reaches the entry...
        assertTrue(entry.contains("JVM Acme JDK/26.0.1"), entry)
        assertTrue(entry.contains("heap JVM defaults (VM options: <none>)"), entry)

        // ...and the RUNNING one does not. These three assertions are the shipped defect,
        // inverted: before the fix the entry carried exactly these values instead.
        assertFalse(entry.contains(System.getProperty("java.vendor")), entry)
        assertFalse(entry.contains("/" + System.getProperty("java.version")), entry)
        ManagementFactory.getRuntimeMXBean().inputArguments
            .filter { it.startsWith("-Xms") || it.startsWith("-Xmx") }
            .forEach { assertFalse(entry.contains(it), "$it leaked into: $entry") }
    }

    @Test
    fun `records the heap flags the forks were actually launched with`(@TempDir dir: File) {
        val results = runArtifacts(
            dir,
            quietCsv,
            log = banner("21.0.11", options = "-Xms8g -Xmx8g -Dfoo=bar"),
        )

        val entry = ThroughputReport
            .renderRun(results, "b861114d", "2026-08-18", "operator throughput")
            .perDrive.first().entry
        assertTrue(entry.contains("heap -Xms8g -Xmx8g"), entry)
        // The non-heap option is not smuggled into the heap field.
        assertFalse(entry.contains("-Dfoo=bar"), entry)
    }

    @Test
    fun `identifies the JVM build from the banner when the invoker JDK is unreadable`() {
        // The REAL sweep's own retained banner (computenet-x9e.4.5), with the invoker
        // pointed at a path that does not exist so the `release` route is unavailable and
        // the outcome does not depend on this machine's installed JDKs.
        val jvm = MeasuringJvm.fromJmhLog(
            """
            # JMH version: 1.37
            # VM version: JDK 26.0.1, OpenJDK 64-Bit Server VM, 26.0.1
            # VM invoker: /nonexistent/openjdk/26.0.1/bin/java
            # VM options: <none>
            """.trimIndent(),
            source = "<fixture>",
        )
        assertEquals("26.0.1", jvm.version)
        assertTrue(jvm.vendor.contains("OpenJDK 64-Bit Server VM"), jvm.vendor)
        assertTrue(jvm.vendor.contains("/nonexistent/openjdk/26.0.1/bin/java"), jvm.vendor)
    }

    @Test
    fun `refuses when the banner and the invoker JDK disagree about the version`(
        @TempDir dir: File,
    ) {
        // JMH prints `# VM version` from the harness process and forks `# VM invoker`.
        // A `-jvm` sweep makes exactly one of the two the measuring JVM, and the log does
        // not say which — so neither is reported.
        val invoker = fakeJdk(dir, implementor = "Acme JDK", version = "26.0.1")
        val failure = assertThrows(MeasuringJvmUnknownException::class.java) {
            MeasuringJvm.fromJmhLog(
                banner("21.0.11", invoker = invoker.absolutePath),
                source = "<fixture>",
            )
        }
        assertTrue(failure.message!!.contains("21.0.11"), failure.message)
        assertTrue(failure.message!!.contains("26.0.1"), failure.message)
    }

    // ---------------------------------------------------------------------------------
    // computenet-x9e.8: the rendered JMH knobs must be the ones the RUN used, not the ones
    // the benchmark class declares.
    //
    // The same defect shape as computenet-hqid above, one field over. `renderRun` filled
    // `RunEnvironment`'s mode/forks/warmup/iters from `ThroughputReport`'s constants —
    // which mirror `OperatorThroughputBenchmark`'s `@BenchmarkMode`/`@Fork`/`@Warmup`/
    // `@Measurement` — so every entry stated `mode=Throughput forks=2 warmup=5 iters=10`
    // whatever the sweep did. JMH's `-f`/`-wi`/`-i` override those annotations, and
    // `OperatorThroughputBenchmark`'s own KDoc documents a `-f 1 -wi 1 -i 1` smoke
    // invocation, so this is reachable rather than theoretical: no shipped entry is known
    // wrong (all three sweeps ran the annotation config) and the next smoke sweep would
    // have been.
    //
    // The tests below FAIL if the substitution returns: the refusals because a log missing
    // a knob line would render successfully again, and the positive one because the entry
    // would carry the constants instead of the recorded values.
    // ---------------------------------------------------------------------------------

    /** A log identical to a well-formed one except that [prefix]'s lines are gone. */
    private fun bannerWithout(prefix: String, invoker: String? = null): String =
        banner("21.0.11", invoker = invoker).lineSequence()
            .filterNot { it.startsWith(prefix) }
            .joinToString("\n")

    @Test
    fun `refuses a run log that does not state the fork count the run used`(
        @TempDir dir: File,
    ) {
        val results = runArtifacts(dir, quietCsv, log = bannerWithout(RunKnobs.FORK_PREFIX))

        val failure = assertThrows(RunKnobsUnknownException::class.java) {
            ThroughputReport.renderRun(results, "b861114d", "2026-08-19", "operator throughput")
        }
        assertTrue(failure.message!!.contains(RunKnobs.FORK_PREFIX), failure.message)
        assertTrue(failure.message!!.contains("fork count"), failure.message)
    }

    @Test
    fun `refuses a run log that does not state the warmup iterations the run used`(
        @TempDir dir: File,
    ) {
        val results = runArtifacts(dir, quietCsv, log = bannerWithout(RunKnobs.WARMUP_PREFIX))

        val failure = assertThrows(RunKnobsUnknownException::class.java) {
            ThroughputReport.renderRun(results, "b861114d", "2026-08-19", "operator throughput")
        }
        assertTrue(failure.message!!.contains(RunKnobs.WARMUP_PREFIX), failure.message)
    }

    @Test
    fun `refuses a run log that does not state the measurement iterations the run used`(
        @TempDir dir: File,
    ) {
        val results = runArtifacts(
            dir,
            quietCsv,
            log = bannerWithout(RunKnobs.MEASUREMENT_PREFIX),
        )

        val failure = assertThrows(RunKnobsUnknownException::class.java) {
            ThroughputReport.renderRun(results, "b861114d", "2026-08-19", "operator throughput")
        }
        assertTrue(failure.message!!.contains(RunKnobs.MEASUREMENT_PREFIX), failure.message)
    }

    @Test
    fun `refuses a run log that does not state the mode the run used`(@TempDir dir: File) {
        val results = runArtifacts(
            dir,
            quietCsv,
            log = bannerWithout(RunKnobs.BENCHMARK_MODE_PREFIX),
        )

        val failure = assertThrows(RunKnobsUnknownException::class.java) {
            ThroughputReport.renderRun(results, "b861114d", "2026-08-19", "operator throughput")
        }
        assertTrue(failure.message!!.contains(RunKnobs.BENCHMARK_MODE_PREFIX), failure.message)
    }

    @Test
    fun `refuses a run log recording an unforked run, which has no fork count`(
        @TempDir dir: File,
    ) {
        // `-f 0`: JMH measures inside the harness JVM and prints this instead of a total.
        // There is no fork count to state, and `@Fork(2)` is not the answer.
        val results = runArtifacts(
            dir,
            quietCsv,
            log = banner(
                "21.0.11",
                knobs = knobBanner(forks = null, forkLine = "N/A, test runs in the host VM"),
            ),
        )

        val failure = assertThrows(RunKnobsUnknownException::class.java) {
            ThroughputReport.renderRun(results, "b861114d", "2026-08-19", "operator throughput")
        }
        assertTrue(failure.message!!.contains("N/A"), failure.message)
    }

    @Test
    fun `refuses a run log recording a run that did no warmup`(@TempDir dir: File) {
        // `-wi 0`: JMH prints `# Warmup: <none>`, and `@Warmup(iterations = 5)` is not the
        // answer for a run that warmed up zero times.
        val results = runArtifacts(
            dir,
            quietCsv,
            log = banner("21.0.11", knobs = knobBanner(warmupIterations = null)),
        )

        val failure = assertThrows(RunKnobsUnknownException::class.java) {
            ThroughputReport.renderRun(results, "b861114d", "2026-08-19", "operator throughput")
        }
        assertTrue(failure.message!!.contains(RunKnobs.NONE), failure.message)
    }

    @Test
    fun `refuses a run log whose fork lines disagree about the total`(@TempDir dir: File) {
        // Two benchmarks run at different `@Fork` counts into one log: `:bench` declares
        // three different configurations, so this is a real way to produce such a file.
        val results = runArtifacts(
            dir,
            quietCsv,
            log = banner("21.0.11", knobs = knobBanner(forks = 2) + knobBanner(forks = 5)),
        )

        val failure = assertThrows(RunKnobsUnknownException::class.java) {
            ThroughputReport.renderRun(results, "b861114d", "2026-08-19", "operator throughput")
        }
        assertTrue(
            failure.message!!.contains("not one run under one configuration"),
            failure.message,
        )
    }

    @Test
    fun `reads the four knobs off a banner, trimming the mode's metric clause`() {
        val knobs = RunKnobs.fromJmhLog(
            """
            # Warmup: 1 iterations, 1 s each
            # Measurement: 1 iterations, 1 s each
            # Benchmark mode: Throughput, ops/time
            # Fork: 1 of 1
            """.trimIndent(),
            source = "<fixture>",
        )
        assertEquals(RunKnobs("Throughput", 1, 1, 1), knobs)
    }

    @Test
    fun `renders the knobs the run recorded, never the ones the benchmark declares`(
        @TempDir dir: File,
    ) {
        // The bead's named fixture: a log recording `-f 1` against an `@Fork(2)`
        // benchmark, with `-wi`/`-i`/mode overridden too so all four fields discriminate.
        val results = runArtifacts(
            dir,
            quietCsv,
            log = banner(
                "21.0.11",
                knobs = knobBanner(
                    mode = "Average time, time/op",
                    warmupIterations = 3,
                    measurementIterations = 4,
                    forks = 1,
                ),
            ),
        )

        val entry = ThroughputReport
            .renderRun(results, "b861114d", "2026-08-19", "operator throughput")
            .perDrive.first { it.drive == Drive.SIM }.entry

        // The RECORDED knobs reach the entry, as one line...
        assertTrue(entry.contains("mode=Average time forks=1 warmup=3 iters=4"), entry)

        // ...and the DECLARED ones do not. These four assertions are the defect, inverted:
        // before the fix the entry carried exactly these values instead.
        assertFalse(entry.contains("mode=${ThroughputReport.JMH_MODE}"), entry)
        assertFalse(entry.contains("forks=${ThroughputReport.FORKS}"), entry)
        assertFalse(entry.contains("warmup=${ThroughputReport.WARMUP_ITERATIONS}"), entry)
        assertFalse(entry.contains("iters=${ThroughputReport.MEASUREMENT_ITERATIONS}"), entry)
    }

    // ---------------------------------------------------------------------------------
    // computenet-yhbd: the rendered CPU model, core count and OS must be the measuring
    // HOST's, not the rendering process's.
    //
    // The same defect shape as computenet-hqid (JVM triple) and computenet-x9e.8 (JMH
    // knobs), one field group over. Unlike those two, no JMH artifact records the host at
    // all, so `renderRun` could not simply parse harder — `OperatorThroughputBenchmark`'s
    // `GraphState.announceHost` (`@Setup(Level.Trial)`) now prints the host facts from
    // INSIDE the measuring fork, onto the same log `MeasuringJvm`/`RunKnobs` already read,
    // and `HostFacts.fromJmhLog` reads them back.
    //
    // The refusal test FAILS if the substitution is restored: a log missing the host
    // banner would render successfully again, using this process's own `HostFacts
    // .captureCurrent()`. The positive test FAILS the same way: the entry would carry
    // THIS machine's CPU/core/OS instead of the fixture's deliberately different ones.
    // ---------------------------------------------------------------------------------

    @Test
    fun `refuses a run log that does not state the host that measured`(@TempDir dir: File) {
        val results = runArtifacts(dir, quietCsv, log = bannerWithout(HostFacts.CPU_MODEL_PREFIX))

        val failure = assertThrows(HostFactsUnknownException::class.java) {
            ThroughputReport.renderRun(results, "b861114d", "2026-08-19", "operator throughput")
        }
        assertTrue(failure.message!!.contains(HostFacts.CPU_MODEL_PREFIX), failure.message)
        assertTrue(failure.message!!.contains("host"), failure.message)
    }

    @Test
    fun `refuses a run log whose host lines disagree`(@TempDir dir: File) {
        // Two benchmarks measured on different hosts concatenated into one log describe
        // no single measuring host, the same shape RunKnobs and MeasuringJvm refuse for
        // their own facts.
        val results = runArtifacts(
            dir,
            quietCsv,
            log = banner("21.0.11", host = hostBanner(cpuModel = "Apple M2 Pro") +
                hostBanner(cpuModel = "AMD EPYC 7763")),
        )

        val failure = assertThrows(HostFactsUnknownException::class.java) {
            ThroughputReport.renderRun(results, "b861114d", "2026-08-19", "operator throughput")
        }
        assertTrue(failure.message!!.contains("not one run on one host"), failure.message)
    }

    @Test
    fun `renders the host the run recorded, never the one doing the rendering`(
        @TempDir dir: File,
    ) {
        // The recorded host is deliberately nothing like the machine running this test
        // (a Mac, per `HostFacts.captureCurrent()` below): a rack CPU model, a core count
        // no laptop has, and a Linux OS string.
        val results = runArtifacts(
            dir,
            quietCsv,
            log = banner(
                "21.0.11",
                host = hostBanner(
                    cpuModel = "Genuine Intel Xeon Platinum 8375C",
                    coreCount = 64,
                    os = "Linux 6.2.0-1019-aws",
                ),
            ),
        )

        val entry = ThroughputReport
            .renderRun(results, "b861114d", "2026-08-19", "operator throughput")
            .perDrive.first { it.drive == Drive.SIM }.entry

        // The RECORDED host reaches the entry...
        assertTrue(entry.contains("Genuine Intel Xeon Platinum 8375C, 64 cores"), entry)
        assertTrue(entry.contains("Linux 6.2.0-1019-aws"), entry)

        // ...and the RUNNING one does not. The fixture must actually differ from the
        // machine running this test for this half to mean anything — assert that too,
        // so a future retarget to a Xeon/Linux CI runner fails loudly instead of quietly
        // stopping to discriminate.
        val runningHost = HostFacts.captureCurrent()
        assertFalse(runningHost.cpuModel == "Genuine Intel Xeon Platinum 8375C", "fixture no longer differs from the running host's CPU")
        assertFalse(runningHost.os == "Linux 6.2.0-1019-aws", "fixture no longer differs from the running host's OS")
        assertFalse(entry.contains(runningHost.cpuModel), entry)
        assertFalse(entry.contains(runningHost.os), entry)
    }

    @Test
    fun `results built by hand render through the same path`() {
        val result = BenchResult(1.0, "ops/s", 0.001, Drive.SIM, env)
        val report = ThroughputReport.renderResults(
            listOf(LabelledResult("UNION insert", result)),
            "2026-08-18",
            "operator throughput",
        )
        assertNotNull(report.perDrive.single().entry)
    }

    // ---------------------------------------------------------------------------------
    // computenet-x9e.10: a sweep whose @Params are NOT subject/direction renders through
    // the shipped path, with no hand-written driver.
    //
    // What the defect cost, before this change: `labelOf` hard-coded `subject` and
    // `direction` — `OperatorThroughputBenchmark`'s two parameters — so `renderRun`
    // refused every other sweep in this module ("cannot be labelled"), including the two
    // named next: `CellFootprintBenchmark` (`family`, `scale`) and `BoundedReadBenchmark`
    // (`scale`). computenet-x9e.6.4 therefore rendered the V1C-BENCH E1 entry through a
    // ~60-line throwaway `E1Render.java` driver against the JMH fat jar; that entry in
    // doc/bench/findings.md says so in as many words. Every honesty-bearing STEP was
    // shipped code, but the invocation was hand-written and uncommitted.
    //
    // These tests fail if the hard-coding returns: the first because a `family`/`scale`
    // file would refuse instead of rendering, and the registry test because the column
    // choice would no longer be declared anywhere that a benchmark's own `@Param`s can be
    // checked against.
    // ---------------------------------------------------------------------------------

    /**
     * `CellFootprintBenchmark`'s columns — `family` and `scale`, and no `subject` or
     * `direction` anywhere in the file.
     *
     * Its one `@Benchmark` method is `realSnapshot`, which names its [Drive] the way
     * `[BEN1-26]` requires, so these rows reach the table on the drive rule unchanged.
     * The third row is deliberately far too dispersed (90/1500 = 0.06, against
     * `NOISE_FLOOR` 0.005): the dispersion NOTE has to stay on this path too, not only on
     * the subject/direction one. It is no longer excluded from the table
     * (`computenet-785b`) — it is rendered with its error bar and flagged beside it.
     */
    private val footprintCsv = listOf(
        """"Benchmark","Mode","Threads","Samples","Score","Score Error (99.9%)","Unit",""" +
            """"Param: family","Param: scale"""",
        """"civictech.bench.micro.CellFootprintBenchmark.realSnapshot","avgt",1,20,12.5,0.02,"us/op","SET_CELL","N1E3"""",
        """"civictech.bench.micro.CellFootprintBenchmark.realSnapshot","avgt",1,20,140.0,0.3,"us/op","SET_CELL","N1E4"""",
        """"civictech.bench.micro.CellFootprintBenchmark.realSnapshot","avgt",1,20,1500.0,90.0,"us/op","MAP_CELL","N1E5"""",
    ).joinToString("\n")

    @Test
    fun `a sweep labelled by family and scale renders through renderRun`(@TempDir dir: File) {
        assertFalse(footprintCsv.contains("subject"), footprintCsv)
        assertFalse(footprintCsv.contains("direction"), footprintCsv)

        val results = runArtifacts(
            dir,
            footprintCsv,
            log = banner("21.0.11", knobs = knobBanner(mode = "AverageTime, time/op")),
        )

        val report = ThroughputReport.renderRun(
            results = results,
            harnessCommitSha = "b861114d",
            date = "2026-08-19",
            subject = "cell footprint",
        )

        val driveReport = report.perDrive.single()
        assertEquals(Drive.REAL, driveReport.drive)
        val entry = driveReport.entry
        assertTrue(entry.contains("| SET_CELL N1E3 | 12.5 ± 0.02 us/op |"), entry)
        assertTrue(entry.contains("| SET_CELL N1E4 | 140.0 ± 0.3 us/op |"), entry)

        // F3's writer produced this, not a second render route: its environment line, its
        // knob line off the run's own banner, and its incomplete-trigger line are all here.
        assertTrue(entry.contains("Harness: b861114d"), entry)
        assertTrue(entry.contains("mode=AverageTime"), entry)
        assertTrue(entry.contains("drive=REAL"), entry)
        assertTrue(entry.contains("MARKED INCOMPLETE"), entry)

        // computenet-785b: the dispersed row is rendered here too, with its error bar,
        // and its dispersion is stated beside the table instead of excluding it.
        assertTrue(entry.contains("| MAP_CELL N1E5 | 1500.0 ± 90.0 us/op |"), entry)

        // computenet-ahn0: this row is 90.0/1500.0 = 0.06 relative dispersion — twelve
        // times NOISE_FLOOR, and it used to be flagged for exactly that. It is no longer
        // flagged, because `CellFootprintBenchmark` now carries its own derived floor
        // (0.398 since computenet-3sua re-derived it under classFloorStatistic; 1.044
        // under the maximum-over-every-observation estimator that replaced, and 0.593
        // under the superseded JBR 25 runs) and 0.06 is well under any of the three. That
        // change is the whole point of the per-class
        // floor: a bound this class cannot clear on a quiet machine was not detecting
        // interference. The row is still rendered with its error bar either way.
        val mapCell = report.dispersions.single { it.label == "MAP_CELL N1E5" }
        assertEquals("CellFootprintBenchmark", mapCell.benchmarkClass)
        assertEquals("realSnapshot", mapCell.benchmarkMethod)
        assertEquals(noiseFloorFor("CellFootprintBenchmark", "realSnapshot"), mapCell.floor)
        assertTrue(hasClassFloor("CellFootprintBenchmark", "realSnapshot"))
        assertEquals(
            emptyList<String>(),
            report.dispersions.filter { it.aboveHarnessSanityBound }.map { it.label },
        )
        assertTrue(report.text().contains("MAP_CELL N1E5 (drive=REAL)"), report.text())
    }

    @Test
    fun `an explicit RowLabel overrides the registered columns`(@TempDir dir: File) {
        // The parameterisation is a caller's to state as well as a benchmark's to register
        // — a results file from a benchmark nobody has registered is rendered by naming its
        // columns here, which is what replaces the throwaway driver.
        val results = runArtifacts(
            dir,
            footprintCsv,
            log = banner("21.0.11", knobs = knobBanner(mode = "AverageTime, time/op")),
        )

        val entry = ThroughputReport.renderRun(
            results = results,
            harnessCommitSha = "b861114d",
            date = "2026-08-19",
            subject = "cell footprint",
            label = RowLabel(params = listOf("scale", "family"), lowercased = setOf("family")),
        ).perDrive.single().entry

        // Reordered, and the chosen column lowercased: both are the label's to decide.
        assertTrue(entry.contains("| N1E3 set_cell | 12.5 ± 0.02 us/op |"), entry)
        assertFalse(entry.contains("SET_CELL"), entry)
    }

    /**
     * `CellFootprintBenchmark` under `-prof gc`, in JMH 1.37's own shape (computenet-6zqz).
     *
     * Every field here was copied off a real run on this host, not invented: the primary
     * row in `us/op`, the four secondary rows JMH contributes under `-prof gc`, the
     * `:<metric key>` suffix that names each of them, and — the fixture's whole reason for
     * existing — the literal `NaN` in the `Score Error` column of `gc.count` and `gc.time`,
     * which are sums rather than means. Before [Metric], that NaN refused the ENTIRE file
     * at [ThroughputReport.parseCsv], so no `-prof gc` sweep could be rendered at all,
     * whatever metric the caller wanted.
     *
     * The `MAP_CELL N1E5` allocation row is deliberately far too dispersed
     * (40000/500000 = 0.08 against `NOISE_FLOOR` 0.005), so the dispersion gate and the
     * omission accounting are exercised on the secondary-metric path too and not only on
     * the primary one. Since `computenet-ahn0` gave this class a floor of its own, 0.08
     * no longer trips that gate — what the fixture still exercises here is the
     * secondary-metric path's *resolution* of the bound, asserted below.
     */
    private val profGcCsv = listOf(
        """"Benchmark","Mode","Threads","Samples","Score","Score Error (99.9%)","Unit",""" +
            """"Param: family","Param: scale"""",
        """"civictech.bench.micro.CellFootprintBenchmark.realSnapshot","avgt",1,20,33.8,0.05,"us/op","SET_CELL","N1E3"""",
        """"civictech.bench.micro.CellFootprintBenchmark.realSnapshot:gc.alloc.rate","avgt",1,20,7465.9,12.0,"MB/sec","SET_CELL","N1E3"""",
        """"civictech.bench.micro.CellFootprintBenchmark.realSnapshot:gc.alloc.rate.norm","avgt",1,20,265247.39,4.15,"B/op","SET_CELL","N1E3"""",
        """"civictech.bench.micro.CellFootprintBenchmark.realSnapshot:gc.count","avgt",1,20,35.0,NaN,"counts","SET_CELL","N1E3"""",
        """"civictech.bench.micro.CellFootprintBenchmark.realSnapshot:gc.time","avgt",1,20,25.0,NaN,"ms","SET_CELL","N1E3"""",
        """"civictech.bench.micro.CellFootprintBenchmark.realSnapshot","avgt",1,20,4100.0,9.0,"us/op","MAP_CELL","N1E5"""",
        """"civictech.bench.micro.CellFootprintBenchmark.realSnapshot:gc.alloc.rate.norm","avgt",1,20,500000.0,40000.0,"B/op","MAP_CELL","N1E5"""",
    ).joinToString("\n")

    @Test
    fun `the gc alloc metric selects its own rows and is not refused by gc count's NaN`() {
        val rows = ThroughputReport.parseCsv(profGcCsv, Metric.GC_ALLOC_RATE_NORM)

        assertEquals(2, rows.size, rows.toString())
        assertEquals(listOf("B/op", "B/op"), rows.map { it.unit })
        assertEquals(listOf(265247.39, 500000.0), rows.map { it.score })

        // The `:gc.alloc.rate.norm` suffix is stripped, so every downstream reader — the
        // registered label columns, the drive rule, the class name — sees the benchmark
        // it always saw.
        assertEquals(listOf("realSnapshot", "realSnapshot"), rows.map { it.method })
        assertEquals("CellFootprintBenchmark", rows.first().benchmarkClass)
        assertEquals(Drive.REAL, ThroughputReport.driveOf(rows.first()))
        assertEquals("SET_CELL N1E3", ThroughputReport.labelOf(rows.first()))
    }

    @Test
    fun `the default metric is still the primary score, on a prof gc file too`() {
        // Also the regression pin for the refusal [Metric] removed: before selection
        // existed this call threw, because gc.count's NaN error was reached while
        // parsing rows nobody had asked for.
        val rows = ThroughputReport.parseCsv(profGcCsv)

        assertEquals(2, rows.size, rows.toString())
        assertEquals(listOf("us/op", "us/op"), rows.map { it.unit })
        assertEquals(listOf(33.8, 4100.0), rows.map { it.score })
    }

    @Test
    fun `a metric the file does not hold is refused, naming the metrics it does`() {
        // The failure this closes is the one that looks successful: a sweep run WITHOUT
        // `-prof gc` writes a complete, parseable results file whose only metric is wall
        // clock. Falling back to it would report us/op under a subject line promising
        // B/op.
        val refusal = assertThrows(ThroughputReportException::class.java) {
            ThroughputReport.parseCsv(footprintCsv, Metric.GC_ALLOC_RATE_NORM)
        }
        assertTrue(refusal.message!!.contains("gc.alloc.rate.norm"), refusal.message)
        assertTrue(refusal.message!!.contains("<primary>"), refusal.message)
        assertTrue(refusal.message!!.contains("-prof gc"), refusal.message)
    }

    @Test
    fun `a NaN dispersion in the SELECTED metric still refuses`() {
        // Selection narrows WHICH rows the honesty rules apply to; it does not relax them.
        val refusal = assertThrows(ThroughputReportException::class.java) {
            ThroughputReport.parseCsv(profGcCsv, Metric.Secondary("gc.time"))
        }
        assertTrue(refusal.message!!.contains("too few"), refusal.message)
        assertTrue(refusal.message!!.contains("gc.time"), refusal.message)
    }

    @Test
    fun `a secondary metric key must be stated`() {
        assertThrows(IllegalArgumentException::class.java) { Metric.Secondary("") }
        assertThrows(IllegalArgumentException::class.java) { Metric.Secondary("  ") }
    }

    @Test
    fun `a prof gc sweep renders its allocation entry through renderRun`(@TempDir dir: File) {
        val results = runArtifacts(
            dir,
            profGcCsv,
            log = banner("21.0.11", knobs = knobBanner(mode = "AverageTime, time/op")),
        )

        val report = ThroughputReport.renderRun(
            results = results,
            harnessCommitSha = "b861114d",
            date = "2026-08-20",
            subject = "bytes allocated per snapshot() call",
            trigger = TriggerClaim.Cited(
                gapId = "G-21 phase 3",
                statement = "INCONCLUSIVE, for the fixture's sake.",
            ),
            metric = Metric.GC_ALLOC_RATE_NORM,
        )

        val entry = report.perDrive.single().entry
        // B/op, not us/op: the entry reports the metric that was asked for, and the unit
        // is carried per row rather than restated by the caller.
        assertTrue(entry.contains("| SET_CELL N1E3 | 265247.39 ± 4.15 B/op |"), entry)
        assertFalse(entry.contains("us/op"), entry)
        assertTrue(entry.contains("Trigger: G-21 phase 3 — INCONCLUSIVE"), entry)

        // The dispersion note is on the secondary-metric path too (computenet-785b:
        // a note beside the table, not an exclusion from it) — and it resolves its bound
        // through the same per-class table the primary path does (computenet-ahn0), so
        // `MAP_CELL N1E5` clears `CellFootprintBenchmark`'s derived floor of 0.398 here
        // as well and is no longer flagged against the global bound.
        val mapCell = report.dispersions.single { it.label == "MAP_CELL N1E5" }
        assertEquals("CellFootprintBenchmark", mapCell.benchmarkClass)
        assertEquals("realSnapshot", mapCell.benchmarkMethod)
        assertEquals(noiseFloorFor("CellFootprintBenchmark", "realSnapshot"), mapCell.floor)
        assertEquals(
            emptyList<String>(),
            report.dispersions.filter { it.aboveHarnessSanityBound }.map { it.label },
        )
        assertTrue(report.text().contains("MAP_CELL N1E5 (drive=REAL)"), report.text())
    }

    /**
     * `BoundedReadBenchmark`'s shape — two `@Benchmark` methods over one `scale` — which is
     * why [RowLabel.includeMethod] exists.
     *
     * The method names here ARE the benchmark's own, as of computenet-7w4e. When this
     * fixture was written they were not: the benchmark's two methods were `direct` and
     * `hostedSnapshotOf`, which name no [Drive], so `driveOf` refused them outright
     * (`[BEN1-26]`) and no label choice reached a table at all — the fixture therefore
     * named the drive-bearing form the benchmark needed, so that what this test decides
     * is the LABEL rule. computenet-7w4e renamed them to `realDirect` and
     * `realHostedSnapshotOf`, so fixture and source now agree, and the drive-rule pin
     * further down this file is what keeps them agreeing.
     */
    private val twoMethodCsv = listOf(
        """"Benchmark","Mode","Threads","Samples","Score","Score Error (99.9%)","Unit",""" +
            """"Param: scale"""",
        """"civictech.bench.micro.BoundedReadBenchmark.realDirect","avgt",1,20,0.0375,1.0E-4,"ms/op","N1E3"""",
        """"civictech.bench.micro.BoundedReadBenchmark.realHostedSnapshotOf","avgt",1,20,0.0488,1.2E-4,"ms/op","N1E3"""",
    ).joinToString("\n")

    @Test
    fun `two rows that would share a label are refused, not rendered ambiguously`() {
        // Labelled by `scale` alone, both rows read "N1E3". FindingsTable cannot see this
        // — the labels are non-blank and there is one per result — so the refusal has to
        // be here, where the columns were chosen.
        val failure = assertThrows(ThroughputReportException::class.java) {
            ThroughputReport.render(
                twoMethodCsv,
                env,
                "2026-08-19",
                "bounded read",
                label = RowLabel(params = listOf("scale")),
            )
        }
        assertTrue(failure.message!!.contains("share a label"), failure.message)
        assertTrue(failure.message!!.contains("'N1E3' x 2"), failure.message)
        assertTrue(failure.message!!.contains("includeMethod"), failure.message)

        // And F3 itself would have rendered it: the collision is invisible to FindingsTable.
        val ambiguous = ThroughputReport.toResults(
            ThroughputReport.parseCsv(twoMethodCsv),
            env,
            RowLabel(params = listOf("scale")),
        )
        assertEquals(listOf("N1E3", "N1E3"), ambiguous.map { it.label })
        assertNotNull(
            Findings.entry(
                date = "2026-08-19",
                subject = "bounded read",
                results = FindingsTable(ambiguous.map { it.result }, ambiguous.map { it.label }),
            )
        )
    }

    @Test
    fun `includeMethod distinguishes rows that share a parameter set`() {
        val entry = ThroughputReport.render(
            twoMethodCsv,
            env,
            "2026-08-19",
            "bounded read",
            label = RowLabel(params = listOf("scale"), includeMethod = true),
        ).perDrive.single().entry

        assertTrue(entry.contains("| realDirect N1E3 | 0.0375 ± 1.0E-4 ms/op |"), entry)
        assertTrue(
            entry.contains("| realHostedSnapshotOf N1E3 | 0.0488 ± 1.2E-4 ms/op |"),
            entry,
        )
    }

    @Test
    fun `a benchmark class with no registered columns is refused, never guessed at`() {
        val csv = twoMethodCsv.replace("BoundedReadBenchmark", "SomeFutureBenchmark")
        val failure = assertThrows(ThroughputReportException::class.java) {
            ThroughputReport.render(csv, env, "2026-08-19", "something new")
        }
        assertTrue(failure.message!!.contains("SomeFutureBenchmark"), failure.message)
        assertTrue(failure.message!!.contains("RowLabel.REGISTERED"), failure.message)
        // The escape hatch the refusal names actually works, on the same file.
        assertNotNull(
            ThroughputReport.render(
                csv,
                env,
                "2026-08-19",
                "something new",
                label = RowLabel(params = listOf("scale"), includeMethod = true),
            ).perDrive.single().entry
        )
    }

    @Test
    fun `a row missing one of its label columns is refused rather than labelled unknown`() {
        val csv = footprintCsv.replace(""""Param: family"""", """"Param: unrelated"""")
        val failure = assertThrows(ThroughputReportException::class.java) {
            ThroughputReport.render(csv, env, "2026-08-19", "cell footprint")
        }
        assertTrue(failure.message!!.contains("no usable 'family' parameter"), failure.message)
        assertTrue(failure.message!!.contains("cannot be labelled"), failure.message)
    }

    @Test
    fun `RowLabel refuses a column set it cannot label with`() {
        assertThrows(IllegalArgumentException::class.java) { RowLabel(params = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            RowLabel(params = listOf("scale", " "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RowLabel(params = listOf("scale", "scale"))
        }
        val failure = assertThrows(IllegalArgumentException::class.java) {
            RowLabel(params = listOf("scale"), lowercased = setOf("family"))
        }
        assertTrue(failure.message!!.contains("family"), failure.message)
    }

    @Test
    fun `the subject-direction columns still render the labels the landed entries carry`() {
        // The default is a named value, not an accident of a signature: three entries in
        // doc/bench/findings.md are labelled `<SUBJECT> insert`/`<SUBJECT> retract`, and
        // this is what has to keep producing them.
        assertEquals(listOf("subject", "direction"), RowLabel.SUBJECT_DIRECTION.params)
        assertEquals(setOf("direction"), RowLabel.SUBJECT_DIRECTION.lowercased)
        assertFalse(RowLabel.SUBJECT_DIRECTION.includeMethod)
        assertEquals(
            RowLabel.SUBJECT_DIRECTION,
            RowLabel.REGISTERED.getValue("OperatorThroughputBenchmark"),
        )
        val row = ThroughputReport.parseCsv(quietCsv).first()
        assertEquals("FILTER insert", ThroughputReport.labelOf(row))
    }

    // ---------------------------------------------------------------------------------
    // The registry is pinned against the benchmark sources themselves.
    //
    // `RowLabel.REGISTERED` has to live in `main` — the `jmh` source set is invisible to
    // both `main` and `test`, the same reason this file's `FORKS`/`WARMUP_ITERATIONS`
    // constants do — so nothing in the type system keeps it in step with the `@Param`s the
    // benchmarks actually declare. This reads the benchmark sources from the checkout and
    // does it by hand.
    //
    // The failure it prevents is not a wrong number but a QUIET one: a sweep that gains a
    // dimension whose registered label omits it renders rows that collide, and while
    // `renderResults` refuses a collision, it refuses at measurement time — after the JMH
    // sweep has been paid for. This says so at `:bench:test` speed instead.
    //
    // On staleness: the mutations this catches (a `@Param` added, renamed or removed, a
    // benchmark class added or deleted) all change the generated `META-INF/BenchmarkList`,
    // which bench/build.gradle.kts already declares as an input of `:bench:test` — so the
    // task should not be reported UP-TO-DATE across them. That is reasoning about JMH's
    // generated resource, not something measured here.
    // ---------------------------------------------------------------------------------

    @Test
    fun `every param-bearing benchmark's registered columns name exactly its own params`() {
        val sources = jmhBenchmarkSources()
        val declared = sources.associate { it.nameWithoutExtension to paramsDeclaredIn(it) }
        val withParams = declared.filterValues { it.isNotEmpty() }
        assertEquals(
            withParams.keys.sorted(),
            RowLabel.REGISTERED.keys.sorted(),
            "RowLabel.REGISTERED must name exactly the @Param-bearing benchmarks; " +
                "declared params were $declared",
        )
        withParams.forEach { (benchmark, params) ->
            assertEquals(
                params,
                RowLabel.REGISTERED.getValue(benchmark).params.toSet(),
                "$benchmark declares @Params $params; its registered label columns are " +
                    "${RowLabel.REGISTERED.getValue(benchmark).params}. A parameter " +
                    "missing from the label is a dimension the table cannot show",
            )
        }
        // The parser is doing real work, not returning empty sets that trivially agree.
        assertEquals(
            setOf("subject", "direction"),
            declared.getValue("OperatorThroughputBenchmark"),
        )
        assertEquals(emptySet<String>(), declared.getValue("SmokeBenchmark"))

        // And the key these entries are read under is the one a parsed row resolves to,
        // so agreeing with the sources here means agreeing at render time.
        val rowClass = ThroughputReport.parseCsv(footprintCsv).first().benchmarkClass
        assertEquals("CellFootprintBenchmark", rowClass)
        assertEquals(
            RowLabel.REGISTERED.getValue(rowClass),
            RowLabel.forBenchmark("civictech.bench.micro.CellFootprintBenchmark.realSnapshot"),
        )
    }

    /**
     * The `@Param`-annotated property names a benchmark source declares.
     *
     * Deliberately crude: a `@Param` on its own line — the shape every benchmark in this
     * module uses, `@Param` / `@JvmField` / `var <name>: <Enum>` — followed within a few
     * lines by the property it annotates. A KDoc mention of `@Param` does not match,
     * because a doc line trims to `*` first.
     */
    private fun paramsDeclaredIn(source: File): Set<String> {
        val lines = source.readLines()
        val property = Regex("""\bvar\s+(\w+)\s*:""")
        return lines.indices
            .filter { lines[it].trim().startsWith("@Param") }
            .mapNotNull { at ->
                (at + 1..minOf(at + 4, lines.lastIndex)).firstNotNullOfOrNull { ahead ->
                    property.find(lines[ahead])?.groupValues?.get(1)
                }
            }
            .toSet()
    }

    /**
     * Every `@Benchmark`-bearing Kotlin source under `bench/src/jmh/kotlin`.
     *
     * Walks the whole `jmh` source set, not one package: the earlier revision of this
     * helper listed only the `Benchmark.kt`-suffixed files of the single package
     * `civictech.bench.micro`, so a
     * benchmark in another package — or in a file not named `...Benchmark.kt` — escaped
     * every pin below (noted in review of computenet-x9e.10, widened by computenet-7w4e).
     * Selection is by CONTENT (`@Benchmark` on a line of its own) rather than by filename
     * for the same reason.
     */
    private fun jmhBenchmarkSources(): List<File> {
        val root = System.getProperty("computenet.repo.root")
            ?: error(
                "System property 'computenet.repo.root' is not set. It must be wired in " +
                    "bench/build.gradle.kts on the :bench `test` task so this test can " +
                    "locate bench/src/jmh/kotlin."
            )
        val dir = File(root, "bench/src/jmh/kotlin")
        check(dir.isDirectory) { "no JMH source set at ${dir.absolutePath}" }
        val sources = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readLines().any { it.trim() == "@Benchmark" } }
            .sortedBy { it.path }
            .toList()
        // Guard against a vacuous pass if the tree moves: this suite knows of five.
        assertTrue(sources.size >= 5, "only ${sources.size} benchmark sources under $dir")
        return sources
    }

    // ---------------------------------------------------------------------------------
    // computenet-7w4e: the two BENCHMARK-side preconditions of `renderRun`, pinned
    // against the sources in bench/src/jmh/kotlin rather than asserted in a comment.
    //
    // computenet-x9e.10 (above) made the LABEL columns parameterisable, which was the
    // renderer half. Two halves live on the benchmarks themselves, and neither is visible
    // to the type system — the `jmh` source set is invisible to `main` and `test` alike:
    //
    // 1. HOST FACTS. `RunEnvironment.forRun`'s JMH-sweep overload accepts host facts ONLY
    //    from `HostFacts.fromJmhLog`, which reads a banner that the measuring benchmark
    //    prints from inside its own fork (`@Setup(Level.Trial)`, computenet-yhbd). A
    //    benchmark without that hook produces logs `renderRun` REFUSES, however well its
    //    rows are labelled — which is what happened to `CellFootprintBenchmark` and
    //    `BoundedReadBenchmark` until this item.
    // 2. DRIVE. `ThroughputReport.driveOf` tokenizes a `@Benchmark` method's name and
    //    requires exactly one `sim`/`real` token, because `[BEN1-26]`/`[BEN1-27]` say a
    //    result must never lose the regime that produced it. `BoundedReadBenchmark`'s
    //    `direct`/`hostedSnapshotOf` named none, and the V1C-BENCH E1 entry was rendered
    //    by a throwaway driver that STATED `Drive.REAL` instead — the substitution those
    //    requirements exist to prevent in shipped code.
    //
    // Both failures are refusals, not wrong numbers, and both are paid for AFTER a JMH
    // sweep has been run. These tests say so at `:bench:test` speed instead — and, more
    // to the point, they say it about a benchmark that does not exist yet.
    // ---------------------------------------------------------------------------------

    @Test
    fun `every benchmark-bearing state class prints the host-facts banner`() {
        val sources = jmhBenchmarkSources()
        val offenders = mutableListOf<String>()
        val checked = mutableListOf<String>()

        sources.forEach { source ->
            if (source.name in NON_MEASURING_SOURCES) return@forEach
            val lines = source.readLines()
            val classes = stateClassesIn(lines)
            val reachable = requiredStateNames(lines, classes)
            val required = classes.filter { it.name in reachable }
            assertTrue(
                required.isNotEmpty(),
                "${source.name} declares a @Benchmark but no @State class reachable from " +
                    "one — the parser below no longer understands this file's shape",
            )
            required.forEach { state ->
                checked += "${source.name}:${state.name}"
                if (!announcesHost(state, classes)) offenders += "${source.name}:${state.name}"
            }
        }

        assertEquals(
            emptyList<String>(),
            offenders,
            "these @State classes are reachable from a @Benchmark method and print no " +
                "host-facts banner, so HostFacts.fromJmhLog refuses every log they " +
                "produce and ThroughputReport.renderRun cannot render them. Add a " +
                "`@Setup(Level.Trial)` hook calling " +
                "`HostFacts.captureCurrent().bannerLines().forEach(::println)`, as " +
                "OperatorThroughputBenchmark.GraphState.announceHost does",
        )

        // The scan is doing real work, not passing on an empty set. These are the state
        // classes reachable from a @Benchmark method today; a NEW one is caught by the
        // assertion above, and this one only guards against the parser going blind.
        assertTrue(
            checked.containsAll(
                listOf(
                    "BoundedReadBenchmark.kt:DirectState",
                    "BoundedReadBenchmark.kt:HostedState",
                    "CellFootprintBenchmark.kt:CellState",
                    "FanOutScalingBenchmark.kt:SimState",
                    "FanOutScalingBenchmark.kt:RealState",
                    "OperatorThroughputBenchmark.kt:SimState",
                    "OperatorThroughputBenchmark.kt:RealState",
                )
            ),
            "the state-class scan found only $checked",
        )
    }

    @Test
    fun `every benchmark method name names exactly one drive`() {
        val sources = jmhBenchmarkSources()
        val checked = mutableMapOf<String, Drive>()
        val offenders = mutableListOf<String>()

        sources.forEach { source ->
            if (source.name in NON_MEASURING_SOURCES) return@forEach
            val methods = benchmarkMethodsIn(source.readLines())
            assertTrue(
                methods.isNotEmpty(),
                "${source.name} contains an `@Benchmark` line but no method was parsed " +
                    "from it — the parser no longer understands this file's shape",
            )
            methods.forEach { method ->
                // Through the SHIPPED tokenizer, not a copy of its rule: what has to hold
                // is that `driveOf` accepts these names, and only `driveOf` decides that.
                val row = JmhRow(
                    benchmark = "civictech.bench.micro.${source.nameWithoutExtension}.$method",
                    mode = "avgt",
                    score = 1.0,
                    scoreError = 0.001,
                    unit = "ms/op",
                    params = emptyMap(),
                )
                val drive = runCatching { ThroughputReport.driveOf(row) }.getOrNull()
                if (drive == null) offenders += "${source.name}:$method"
                else checked["${source.name}:$method"] = drive
            }
        }

        assertEquals(
            emptyList<String>(),
            offenders,
            "these @Benchmark methods do not name exactly one drive, so " +
                "ThroughputReport.driveOf refuses their rows [BEN1-26]/[BEN1-27]. Put " +
                "`sim` or `real` in the method name — do not state the drive at the " +
                "render site instead, which is the substitution those requirements exist " +
                "to prevent",
        )

        // Again: real work, and the drive each name resolves to is the one intended.
        assertEquals(Drive.REAL, checked["BoundedReadBenchmark.kt:realDirect"], "$checked")
        assertEquals(
            Drive.REAL,
            checked["BoundedReadBenchmark.kt:realHostedSnapshotOf"],
            "$checked",
        )
        assertEquals(Drive.REAL, checked["CellFootprintBenchmark.kt:realSnapshot"], "$checked")
        assertEquals(Drive.SIM, checked["FanOutScalingBenchmark.kt:sim"], "$checked")
        assertEquals(Drive.REAL, checked["OperatorThroughputBenchmark.kt:real"], "$checked")
    }

    /**
     * The exemption above is not a free pass — it is bounded by a structural fact, and
     * this is the test that keeps it bounded.
     *
     * [NON_MEASURING_SOURCES] holds benchmarks that are not measurements: today only
     * `SmokeBenchmark`, the permanent `@Benchmark`-discovery sentinel `[BEN1-06]`, whose
     * own KDoc says it "is not a measurement and is not meant to become one". Exempting it
     * is safe for one checkable reason: it declares no `@Param`, so
     * [RowLabel.forBenchmark] has nothing to register and REFUSES its rows outright —
     * `renderRun` therefore cannot produce a findings entry from it at all, and a missing
     * banner or an unnamed drive can only ever cost a refusal, never a wrong number.
     *
     * The moment that stops being true — a `@Param` added, or the class registered in
     * [RowLabel.REGISTERED] — this test fails and the exemption has to be earned again by
     * adding the hook and naming the drive.
     */
    @Test
    fun `each exempt benchmark is structurally unrenderable, which is what earns the exemption`() {
        val sources = jmhBenchmarkSources()
        val exempt = sources.filter { it.name in NON_MEASURING_SOURCES }
        assertEquals(
            NON_MEASURING_SOURCES,
            exempt.map { it.name }.toSet(),
            "an exempt source is named that no longer exists under bench/src/jmh/kotlin",
        )
        exempt.forEach { source ->
            assertEquals(
                emptySet<String>(),
                paramsDeclaredIn(source),
                "${source.name} is exempt from the banner and drive rules because it " +
                    "declares no @Param and so cannot be rendered; it now declares one",
            )
            assertFalse(
                RowLabel.REGISTERED.containsKey(source.nameWithoutExtension),
                "${source.name} is exempt from the banner and drive rules because " +
                    "RowLabel.REGISTERED refuses it; it is now registered",
            )
        }
    }

    /**
     * The two scans above, run against synthetic sources whose answers are known.
     *
     * Without this, a parser that silently stopped recognising `@Setup(Level.Trial)` or
     * `@Benchmark` would make both pins vacuous while staying green — the failure mode
     * they exist to prevent, one level up. So each scan is exercised on a source it must
     * ACCEPT and a source it must REJECT.
     */
    @Test
    fun `the source scans discriminate, on inputs whose answers are known`() {
        val withHook = """
            |@State(Scope.Thread)
            |open class Announcing {
            |    @Setup(Level.Trial)
            |    fun announceHost() {
            |        println()
            |        HostFacts.captureCurrent().bannerLines().forEach(::println)
            |    }
            |}
        """.trimMargin().lines()
        val withoutHook = """
            |@State(Scope.Thread)
            |open class Silent {
            |    @Setup(Level.Trial)
            |    fun populate() {
            |        cell = build()
            |    }
            |}
        """.trimMargin().lines()
        val inherited = """
            |@State(Scope.Thread)
            |open class Base(private val drive: Drive) {
            |    @Setup(Level.Trial)
            |    fun announceHost() {
            |        HostFacts.captureCurrent().bannerLines().forEach(::println)
            |    }
            |}
            |
            |@State(Scope.Thread)
            |open class Leaf : Base(Drive.SIM)
        """.trimMargin().lines()

        fun announces(lines: List<String>, name: String): Boolean {
            val classes = stateClassesIn(lines)
            val cls = classes.single { it.name == name }
            return announcesHost(cls, classes)
        }

        assertTrue(announces(withHook, "Announcing"))
        assertFalse(announces(withoutHook, "Silent"))
        // A hook the subclass inherits counts — FanOutScalingBenchmark's SimState/RealState
        // have empty bodies and get theirs from RigState.
        assertTrue(announces(inherited, "Leaf"))
        // ...and a supertype's ctor parameter is not mistaken for a supertype: `Base` has
        // no supertype at all, despite the `:` in `(private val drive: Drive)`.
        assertEquals(emptyList<String>(), stateClassesIn(inherited).single { it.name == "Base" }.supertypes)

        // The @Benchmark method scan: a real declaration, and a KDoc mention that must not
        // count (a doc line trims to `*` first — the same reasoning paramsDeclaredIn uses).
        val methods = """
            |/**
            | * @Benchmark
            | * fun documented(state: S)
            | */
            |@Benchmark
            |fun realThing(state: S, blackhole: Blackhole) {
            |}
        """.trimMargin().lines()
        assertEquals(listOf("realThing"), benchmarkMethodsIn(methods))

        // And the required-state scan resolves a @Benchmark's parameter type, plus a
        // @State class that declares the @Benchmark itself (SmokeBenchmark's shape).
        val reachable = """
            |@State(Scope.Thread)
            |open class Used {
            |}
            |
            |@State(Scope.Thread)
            |open class Unused {
            |}
            |
            |@Benchmark
            |fun realThing(state: Used, blackhole: Blackhole) {
            |}
        """.trimMargin().lines()
        assertEquals(setOf("Used"), requiredStateNames(reachable, stateClassesIn(reachable)))
        val selfHosting = """
            |@State(Scope.Benchmark)
            |open class Sentinel {
            |    @Benchmark
            |    fun baseline(blackhole: Blackhole) {
            |    }
            |}
        """.trimMargin().lines()
        assertEquals(
            setOf("Sentinel"),
            requiredStateNames(selfHosting, stateClassesIn(selfHosting)),
        )
    }

    /**
     * A `@State`-annotated class as the source scans see it: its name, the supertypes its
     * header names, and the lines of its declaration.
     */
    private data class StateClass(
        val name: String,
        val supertypes: List<String>,
        val lines: List<String>,
    )

    /**
     * The `@State` classes a JMH benchmark source declares.
     *
     * Deliberately crude, in the same spirit as [paramsDeclaredIn] and for the same
     * reason — the `jmh` source set is not on this module's test classpath, so there is
     * nothing to reflect over and the sources have to be read as text. It assumes the
     * shape every benchmark in this module uses: `@State(...)` on its own line, the class
     * declaration on the next, and — when the class has a body — its closing brace alone
     * on a line at the declaration's own indentation. A class with no body (
     * `open class SimState : RigState(Drive.SIM)`) is one line.
     *
     * `the source scans discriminate` exercises this on inputs whose answers are known,
     * so a shape it stops understanding fails loudly instead of quietly agreeing.
     */
    private fun stateClassesIn(lines: List<String>): List<StateClass> {
        val declaration = Regex("""^\s*(?:open\s+|abstract\s+|sealed\s+|final\s+|public\s+)*class\s+(\w+)""")
        return lines.indices
            .filter { lines[it].trim().startsWith("@State") }
            .mapNotNull { at ->
                val declAt = (at + 1..minOf(at + 3, lines.lastIndex))
                    .firstOrNull { declaration.containsMatchIn(lines[it]) }
                    ?: return@mapNotNull null
                val header = lines[declAt]
                val name = declaration.find(header)!!.groupValues[1]
                val indent = header.takeWhile { it == ' ' }.length
                val closing = " ".repeat(indent) + "}"
                val end = if (header.trimEnd().endsWith("{")) {
                    (declAt + 1..lines.lastIndex).firstOrNull { lines[it] == closing }
                        ?: lines.lastIndex
                } else {
                    declAt
                }
                StateClass(name, supertypesOf(header), lines.subList(declAt, end + 1))
            }
    }

    /**
     * The supertype names a one-line class header states, or empty.
     *
     * The `:` that starts a supertype list is the first one at paren depth ZERO — a
     * constructor parameter's own `: Type` sits inside the parentheses, so
     * `open class RigState(private val drive: Drive) {` declares no supertype and a naive
     * `substringAfter(":")` would report `Drive`.
     */
    private fun supertypesOf(header: String): List<String> {
        var depth = 0
        var colon = -1
        for ((index, char) in header.withIndex()) {
            when (char) {
                '(', '<' -> depth++
                ')', '>' -> depth--
                ':' -> if (depth == 0) { colon = index; break }
            }
        }
        if (colon < 0) return emptyList()
        return header.substring(colon + 1)
            .removeSuffix("{")
            .trim()
            .split(',')
            .mapNotNull { Regex("""^\s*(\w+)""").find(it)?.groupValues?.get(1) }
    }

    /** Whether [state] prints the host-facts banner, itself or through a supertype. */
    private fun announcesHost(
        state: StateClass,
        all: List<StateClass>,
        seen: MutableSet<String> = mutableSetOf(),
    ): Boolean {
        if (!seen.add(state.name)) return false
        val hookAt = state.lines.indices.filter { state.lines[it].trim() == "@Setup(Level.Trial)" }
        val own = hookAt.any { at ->
            // The hook's own body: up to the next annotated member, so a `@Setup` that
            // populates a fixture cannot borrow a banner printed by a different member.
            val end = (at + 1..state.lines.lastIndex)
                .firstOrNull { state.lines[it].trim().startsWith("@") }
                ?: state.lines.size
            state.lines.subList(at, end)
                .any { it.contains("HostFacts.captureCurrent().bannerLines()") }
        }
        if (own) return true
        return state.supertypes.any { name ->
            all.firstOrNull { it.name == name }?.let { announcesHost(it, all, seen) } ?: false
        }
    }

    /** The `@Benchmark` method names a JMH benchmark source declares. */
    private fun benchmarkMethodsIn(lines: List<String>): List<String> {
        val signature = Regex("""\bfun\s+(\w+)\s*\(""")
        return lines.indices
            .filter { lines[it].trim() == "@Benchmark" }
            .mapNotNull { at ->
                (at + 1..minOf(at + 3, lines.lastIndex)).firstNotNullOfOrNull { ahead ->
                    signature.find(lines[ahead])?.groupValues?.get(1)
                }
            }
    }

    /**
     * The `@State` classes a `@Benchmark` method in this source can actually reach: the
     * ones named as a `@Benchmark` parameter type, plus any that declares a `@Benchmark`
     * itself (`SmokeBenchmark`'s shape — the state class IS the benchmark class).
     *
     * A `@State` class reachable from no `@Benchmark` — `FanOutScalingBenchmark`'s
     * `RigState`, an abstract base — is not required to announce on its own account; its
     * subclasses are, and they inherit the hook from it.
     */
    private fun requiredStateNames(lines: List<String>, classes: List<StateClass>): Set<String> {
        val names = classes.map { it.name }.toSet()
        val signature = Regex("""\bfun\s+\w+\s*\(([^)]*)\)""")
        val parameterTypes = lines.indices
            .filter { lines[it].trim() == "@Benchmark" }
            .flatMap { at ->
                (at + 1..minOf(at + 3, lines.lastIndex))
                    .mapNotNull { signature.find(lines[it])?.groupValues?.get(1) }
                    .flatMap { params ->
                        params.split(',').mapNotNull {
                            Regex(""":\s*(\w+)""").find(it)?.groupValues?.get(1)
                        }
                    }
            }
            .toSet()
        val selfHosting = classes.filter { cls ->
            cls.lines.any { it.trim() == "@Benchmark" }
        }.map { it.name }
        return (parameterTypes intersect names) + selfHosting
    }

    private companion object {

        /**
         * Benchmark sources exempt from the banner and drive rules because they are not
         * measurements — see
         * `each exempt benchmark is structurally unrenderable, which is what earns the
         * exemption` for the fact that bounds the exemption and fails when it stops
         * holding.
         */
        val NON_MEASURING_SOURCES: Set<String> = setOf("SmokeBenchmark.kt")
    }
}

/**
 * The `@Tag("bench")` entry point that renders a REAL JMH results file.
 *
 * Tagged, and therefore excluded from the default test task unconditionally
 * (`[BEN1-09]`..`[BEN1-11]`) — it is an invocation surface for the measurement tasks,
 * not a check. Run it as:
 *
 * ```
 * ./gradlew :bench:test -PbenchOnly=true --rerun \
 *   --tests 'civictech.bench.micro.ThroughputReportRenderTest' \
 *   -Dcivictech.bench.jmhResults=/abs/path/throughput.csv \
 *   -Dcivictech.bench.harnessSha=$(git rev-parse --short HEAD)
 * ```
 *
 * The measuring JVM, the JMH knobs the run resolved, AND the CPU/core/OS of the host that
 * measured all come from the run log that the sweep teed beside that results file
 * (`throughput.csv` -> `throughput.log`, see [ThroughputReport.runLogFor]) — the last of
 * those three because `OperatorThroughputBenchmark`'s `@Setup(Level.Trial)` hook prints
 * it from inside the measuring fork onto that same log. Only the harness SHA is passed
 * in, because no artifact records it.
 *
 * If the log is missing — or carries no `# Fork:`/`# Warmup:`/`# Measurement:`/
 * `# Benchmark mode:`/host-facts line — this test FAILS rather than rendering an entry
 * describing the Gradle test JVM, the benchmark class's declared annotation values, or
 * this process's own CPU/core/OS. See `computenet-hqid` and the two entries in
 * `doc/bench/findings.md` that shipped before it, `computenet-x9e.8` for the knobs, and
 * `computenet-yhbd` for the host.
 */
@Tag("bench")
class ThroughputReportRenderTest {

    @Test
    fun `renders the JMH results file named by civictech dot bench dot jmhResults`() {
        val path = System.getProperty("civictech.bench.jmhResults")
        requireNotNull(path?.takeIf { it.isNotBlank() }) {
            "set -Dcivictech.bench.jmhResults=<path to a JMH -rf csv results file>"
        }
        val sha = System.getProperty("civictech.bench.harnessSha")
        requireNotNull(sha?.takeIf { it.isNotBlank() }) {
            "set -Dcivictech.bench.harnessSha=<git rev-parse --short HEAD>; the results " +
                "file does not record which harness commit produced it, and " +
                "RunEnvironment refuses to exist without it"
        }
        val file = File(path!!)
        require(file.isFile) { "no JMH results file at ${file.absolutePath}" }

        val report = ThroughputReport.renderRun(
            results = file,
            harnessCommitSha = sha!!,
            date = System.getProperty("civictech.bench.date")
                ?: java.time.LocalDate.now().toString(),
            subject = System.getProperty("civictech.bench.subject")
                ?: "operator throughput over the BEN1 micro-graphs",
        )
        // Printed, never written: appending an entry to doc/bench/findings.md is the
        // measurement task's hand step, performed by whoever can vouch for the run.
        println(report.text())
    }
}
