package civictech.bench.micro

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.Findings
import civictech.bench.FindingsRefusalException
import civictech.bench.FindingsTable
import civictech.bench.NOISE_FLOOR
import civictech.bench.Reportability
import civictech.bench.RunEnvironment
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
import java.io.File

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
 * It captures the host half of [RunEnvironment] with [RunEnvironment.capture] and takes
 * the JMH half from the results file's own configuration where the CSV states it, from
 * the constants `OperatorThroughputBenchmark` declares otherwise — those annotations are
 * the run's configuration, which is exactly why that benchmark keeps them explicit.
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

        val env = RunEnvironment.capture(
            jmhMode = ThroughputReport.JMH_MODE,
            forkCount = ThroughputReport.FORKS,
            warmupIterations = ThroughputReport.WARMUP_ITERATIONS,
            measurementIterations = ThroughputReport.MEASUREMENT_ITERATIONS,
            harnessCommitSha = sha!!,
        )
        val report = ThroughputReport.render(
            csv = file.readText(),
            env = env,
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
