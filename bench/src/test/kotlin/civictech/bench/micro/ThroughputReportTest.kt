package civictech.bench.micro

import civictech.bench.BenchResult
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
        assertTrue(report.omissions.isEmpty())

        val sim = report.perDrive.single { it.drive == Drive.SIM }.entry!!
        assertTrue(sim.contains("drive=SIM"), sim)
        assertFalse(sim.contains("drive=REAL"), sim)
        assertTrue(sim.contains("| FILTER insert | 120000.0 ± 240.0 ops/s |"), sim)
        assertTrue(sim.contains("| FILTER retract | 90000.0 ± 180.0 ops/s |"), sim)
        // The REAL rows are in the other entry, never in this one.
        assertFalse(sim.contains("45000.0"), sim)

        val real = report.perDrive.single { it.drive == Drive.REAL }.entry!!
        assertTrue(real.contains("drive=REAL"), real)
        assertTrue(real.contains("| FILTER insert | 45000.0 ± 90.0 ops/s |"), real)

        // Findings' own environment and trigger lines, rendered by F3, not re-derived here.
        assertTrue(sim.contains("Harness: b861114d"), sim)
        assertTrue(sim.contains("forks=2 warmup=5 iters=10"), sim)
        assertTrue(sim.contains("MARKED INCOMPLETE"), sim)

        val text = report.text()
        assertTrue(text.contains("Omitted rows (drive=SIM):\n- none"), text)
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
    fun `an Unreportable row is excluded from the table and named in the output`() {
        val report = ThroughputReport.render(noisyCsv, env, "2026-08-18", "operator throughput")
        val entry = report.perDrive.single().entry!!

        // Excluded from the table...
        assertTrue(entry.contains("| FILTER insert |"), entry)
        assertFalse(entry.contains("GROUP_BY_MIN"), entry)

        // ...and named, with the threshold it failed against, in the renderer's output.
        assertEquals(1, report.omissions.size)
        val omission = report.omissions.single()
        assertEquals("GROUP_BY_MIN retract", omission.label)
        assertEquals(Drive.SIM, omission.drive)
        val text = report.text()
        assertTrue(text.contains("GROUP_BY_MIN retract"), text)
        assertTrue(text.contains("exceeds NOISE_FLOOR $NOISE_FLOOR"), text)
        assertTrue(text.contains("excluded from the table"), text)
    }

    @Test
    fun `the excluded row is one F3 would itself refuse to render`() {
        val noisy = ThroughputReport.toResults(ThroughputReport.parseCsv(noisyCsv), env)
            .single { it.label.startsWith("GROUP_BY_MIN") }
            .result
        assertEquals(Reportability.Unreportable, classify(noisy))

        // Proof the exclusion is not a bypass: handed to Findings directly, the same row
        // refuses the whole entry. The renderer excludes-and-names precisely because F3
        // would otherwise (correctly) refuse everything alongside it.
        val failure = assertThrows(FindingsRefusalException::class.java) {
            Findings.entry(
                date = "2026-08-18",
                subject = "operator throughput",
                results = FindingsTable(listOf(noisy), listOf("GROUP_BY_MIN retract")),
            )
        }
        assertTrue(failure.message!!.contains("Unreportable"), failure.message)
    }

    @Test
    fun `a drive whose every row is Unreportable renders no table and says so`() {
        val csv = listOf(
            header,
            """"civictech.bench.micro.OperatorThroughputBenchmark.real","thrpt",1,20,90000.0,27000.0,"ops/s","INSERT","QUORUM"""",
        ).joinToString("\n")
        val report = ThroughputReport.render(csv, env, "2026-08-18", "operator throughput")
        val driveReport = report.perDrive.single()
        assertNull(driveReport.entry)
        assertEquals(1, driveReport.omitted.size)
        val text = report.text()
        assertTrue(text.contains("no entry for drive=REAL"), text)
        assertTrue(text.contains("QUORUM insert"), text)
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
        assertTrue(report.perDrive.all { it.entry!!.contains("Trigger: G-21 phase 3") })
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
     * Defaults describe a host that is NOT this test-running machine's own (see the
     * `env` fixture above, which shares these values so JVM/knob-focused tests above stay
     * unaffected by this addition). The computenet-yhbd tests below vary [cpuModel] to
     * exercise the refusal and the recorded-vs-running distinction.
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
            .perDrive.first { it.drive == Drive.SIM }.entry!!

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
            .perDrive.first().entry!!
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
            .perDrive.first { it.drive == Drive.SIM }.entry!!

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
            .perDrive.first { it.drive == Drive.SIM }.entry!!

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
