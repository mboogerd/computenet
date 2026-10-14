package civictech.bench.graphalytics

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.Findings
import civictech.bench.FindingsTable
import civictech.bench.MeasuringJvm
import civictech.bench.RunEnvironment
import civictech.bench.TriggerClaim
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.time.LocalDate

/**
 * The opt-in BB-GRA findings entry point. It is a SIM work measurement, not a JMH
 * timing run: five deterministic seeds per dataset produce a mean step count and the
 * seed spread as its dispersion. The five blocked rows are emitted by [coverageFor]
 * because KE5 is not present; no bench-side iteration counter is introduced.
 */
@Tag("bench")
class GraFindingsRenderTest {

    @Test
    fun `renders the KE5-independent Graphalytics coverage entry`() {
        val harnessSha = requireNotNull(System.getProperty("civictech.bench.harnessSha")) {
            "set -Dcivictech.bench.harnessSha=<git rev-parse HEAD>"
        }.takeIf { it.isNotBlank() }
            ?: error("civictech.bench.harnessSha must not be blank")
        val env = RunEnvironment.forRun(
            measuringJvm = inProcessMeasuringJvm(),
            jmhMode = "Graphalytics LCC SIM work steps (JUnit @Tag(bench) probe)",
            forkCount = 1,
            warmupIterations = 1,
            measurementIterations = SEEDS.count(),
            harnessCommitSha = harnessSha,
        )

        val measured = Datasets.all().map { dataset ->
            dataset to SEEDS.map { seed ->
                val record = GraRun.run(
                    dataset = dataset,
                    algorithm = Algorithm.LCC,
                    drive = Drive.SIM,
                    seed = seed,
                    env = env,
                )
                require(record is GraRecord.Validated) {
                    "LCC did not validate for ${dataset.name} at seed $seed"
                }
                record
            }
        }

        val results = measured.flatMap { (dataset, runs) ->
            listOf(
                result(dataset, "load", runs.map { (it.load as Figure.Work).steps }, env),
                result(dataset, "compute", runs.map { (it.compute as Figure.Work).steps }, env),
            )
        }
        val records = measured.flatMap { it.second }
        val coverage = coverageFor(records)
        val sourceCommit = records.map { it.header.dataset.sourceCommit }.distinct().single()
        val loadMode = records.map {
            "${it.header.loadMode.durability}/${it.header.loadMode.ingress}"
        }.distinct().single()
        val entry = Findings.entry(
            date = System.getProperty("civictech.bench.date") ?: LocalDate.now().toString(),
            subject = "BB-GRA — LDBC Graphalytics coverage after the KE5-independent slice " +
                "(LCC validated; PR, CDLP, BFS, SSSP, WCC blocked on E5.2/E5.3[/E5.4])",
            results = FindingsTable(
                results = results,
                labels = results.mapIndexed { index, _ ->
                    measured[index / 2].first.name + " " + if (index % 2 == 0) "load" else "compute"
                },
            ),
            trigger = TriggerClaim.Cited(
                gapId = "G-41",
                statement = "INCONCLUSIVE for cycle regions: five algorithms remain blocked on KE5 constructs; LCC validates.",
            ),
        )

        buildString {
            append(entry)
            appendLine()
            appendLine()
            appendLine("Specification: $SPEC_VERSION")
            appendLine("Dataset source commit: $sourceCommit")
            appendLine("Load mode: $loadMode")
            appendLine()
            append(coverage.render())
        }.also(::println)
    }

    private fun result(
        dataset: DatasetRef,
        phase: String,
        steps: List<Long>,
        env: RunEnvironment,
    ): BenchResult {
        require(steps.isNotEmpty()) { "no $phase steps recorded for ${dataset.name}" }
        val spread = steps.maxOrNull()!! - steps.minOrNull()!!
        return BenchResult(
            value = steps.average(),
            unit = "steps",
            dispersion = spread.toDouble(),
            drive = Drive.SIM,
            env = env,
        )
    }

    private fun inProcessMeasuringJvm(): MeasuringJvm {
        val vendor = System.getProperty("java.vendor").orEmpty().ifBlank { "unknown vendor" }
        val vendorVersion = System.getProperty("java.vendor.version")?.takeIf { it.isNotBlank() }
        val version = requireNotNull(System.getProperty("java.version")) {
            "system property java.version is not set"
        }
        val heapFlags = ManagementFactory.getRuntimeMXBean().inputArguments
            .filter { argument -> HEAP_FLAG_PREFIXES.any(argument::startsWith) }
        return MeasuringJvm(
            vendor = vendorVersion?.let { "$vendor ($it)" } ?: vendor,
            version = version,
            heapSettings = heapFlags.takeIf { it.isNotEmpty() }
                ?.joinToString(" ")
                ?: "JVM defaults (in-process SIM findings renderer)",
        )
    }

    private companion object {
        val SEEDS = 1L..5L
        val HEAP_FLAG_PREFIXES = listOf(
            "-Xms", "-Xmx", "-Xmn",
            "-XX:MinHeapSize", "-XX:InitialHeapSize", "-XX:MaxHeapSize",
            "-XX:MinRAMPercentage", "-XX:InitialRAMPercentage", "-XX:MaxRAMPercentage",
            "-XX:MaxRAM=",
        )
    }
}
