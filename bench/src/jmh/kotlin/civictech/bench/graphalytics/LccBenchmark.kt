package civictech.bench.graphalytics

import civictech.bench.Drive
import civictech.bench.HostFacts
import civictech.bench.MeasuringJvm
import civictech.bench.RunEnvironment
import civictech.bench.micro.BoundedReadFixtures
import civictech.bench.micro.ThroughputReport
import civictech.cell.durability.Journal
import org.openjdk.jmh.annotations.AuxCounters
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * REAL-drive LCC timing over the checked-in Graphalytics examples.
 *
 * Both benchmark methods execute the complete two-phase [GraRun] protocol
 * (load, compute, validation); [GraRun] cannot run its phases separately and
 * is not changed by this task. Under `Mode.AverageTime` JMH's primary score is
 * the wall time of the whole `@Benchmark` call, so it is the WHOLE-RUN time for
 * both methods and says nothing about one phase.
 *
 * The per-phase figures ([BB-GRA-07]) are the `@AuxCounters` secondary results
 * `getLoadNanos` (reported by [realLoad]) and `getComputeNanos`
 * (reported by [realCompute]): each is the record's own interval for that
 * phase, taken from [GraRun]'s `Figure.Duration`. Read those secondary rows,
 * not the primary score, as the load and compute times; the primary score is
 * not a phase figure (it is a timing of the whole invocation with per-invocation
 * fixtures excluded, and was observed not to equal either phase).
 *
 * Caveat: JMH aggregates `@AuxCounters` `EVENTS` secondary rows by SUM over the
 * measurement iterations, so the console table's secondary `Score` is
 * `Cnt` x the mean per-iteration phase time. The per-iteration values (the
 * phase mean in ns) are the `rawData` of the `-rf json` result; the console
 * `Score` divided by `Cnt` is their mean.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(1)
@Fork(ThroughputReport.FORKS)
@Warmup(
    iterations = ThroughputReport.WARMUP_ITERATIONS,
    time = ThroughputReport.ITERATION_SECONDS,
    timeUnit = TimeUnit.SECONDS,
)
@Measurement(
    iterations = ThroughputReport.MEASUREMENT_ITERATIONS,
    time = ThroughputReport.ITERATION_SECONDS,
    timeUnit = TimeUnit.SECONDS,
)
@State(Scope.Benchmark)
open class LccBenchmark {

    @Param("example-directed", "example-undirected")
    @JvmField
    var dataset: String = "example-directed"

    @Param("volatile", "wal-bound", "batched-8", "batched-ingress-64")
    @JvmField
    var loadMode: String = LoadModes.VOLATILE

    private lateinit var trialDirectory: Path
    private lateinit var environment: RunEnvironment
    private lateinit var invocationDirectory: Path
    private var journal: Journal? = null
    private var dispatchBatch: Int = 1

    /** Print facts from the measuring fork and establish the run environment once. */
    @Setup(Level.Trial)
    fun openTrial() {
        trialDirectory = Files.createTempDirectory("computenet-lcc-benchmark-")
        val hostFacts = HostFacts.captureCurrent()
        println()
        HostFacts.captureCurrent().bannerLines().forEach(::println)
        val measuringJvm = currentMeasuringJvm()
        environment = RunEnvironment(
            jvmVendor = measuringJvm.vendor,
            jvmVersion = measuringJvm.version,
            heapSettings = measuringJvm.heapSettings,
            cpuModel = hostFacts.cpuModel,
            coreCount = hostFacts.coreCount,
            os = hostFacts.os,
            jmhMode = "AverageTime",
            forkCount = ThroughputReport.FORKS,
            warmupIterations = ThroughputReport.WARMUP_ITERATIONS,
            measurementIterations = ThroughputReport.MEASUREMENT_ITERATIONS,
            harnessCommitSha = harnessCommitSha(),
        )
    }

    /** Give each invocation a fresh journal file so prior runs cannot alter its cost. */
    @Setup(Level.Invocation)
    fun openInvocation() {
        invocationDirectory = Files.createTempDirectory(trialDirectory, "invocation-")
        val resolved = LoadModes.resolve(loadMode, invocationDirectory)
        journal = resolved.first
        dispatchBatch = resolved.second
    }

    @TearDown(Level.Invocation)
    fun closeInvocation() {
        deleteTree(invocationDirectory)
        journal = null
    }

    @TearDown(Level.Trial)
    fun closeTrial() {
        deleteTree(trialDirectory)
    }

    /**
     * Per-phase secondary results; the primary score of both benchmarks is whole-run time.
     *
     * JMH reads `@AuxCounters` getters once at the end of each iteration, so each
     * exposed value is the MEAN phase interval per invocation in that iteration,
     * in nanoseconds (the sums and the count are private and not reported). The
     * console summary adds these per-iteration values across iterations; see the
     * class KDoc.
     */
    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.EVENTS)
    open class Phases {
        private var invocations = 0L
        private var loadSum = 0L
        private var computeSum = 0L

        /** Same banner as the benchmark state: a state class reachable from a benchmark must print it. */
        @Setup(Level.Trial)
        fun announceHost() {
            HostFacts.captureCurrent().bannerLines().forEach(::println)
        }

        @Setup(Level.Iteration)
        fun reset() {
            invocations = 0
            loadSum = 0
            computeSum = 0
        }

        internal fun recordLoad(nanos: Long) {
            invocations++
            loadSum += nanos
        }

        internal fun recordCompute(nanos: Long) {
            invocations++
            computeSum += nanos
        }

        /** Mean GraRun load interval per invocation, in ns (reported by [realLoad]). */
        fun getLoadNanos(): Long = if (invocations == 0L) 0 else loadSum / invocations

        /** Mean GraRun compute interval per invocation, in ns (reported by [realCompute]). */
        fun getComputeNanos(): Long = if (invocations == 0L) 0 else computeSum / invocations
    }

    /** Whole run as primary score; `getLoadNanos` is the load phase alone. */
    @Benchmark
    fun realLoad(blackhole: Blackhole, phases: Phases) {
        phases.recordLoad(run(blackhole, "load") { it.load })
    }

    /** Whole run as primary score; `getComputeNanos` is the compute phase alone. */
    @Benchmark
    fun realCompute(blackhole: Blackhole, phases: Phases) {
        phases.recordCompute(run(blackhole, "compute") { it.compute })
    }

    private fun run(
        blackhole: Blackhole,
        phase: String,
        figure: (GraRecord.Validated) -> Figure,
    ): Long {
        val record = GraRun.run(
            dataset = datasetRef(),
            drive = Drive.REAL,
            journal = journal,
            dispatchBatch = dispatchBatch,
            env = environment,
        )
        blackhole.consume(record)
        val validated = when (record) {
            is GraRecord.Validated -> record
            is GraRecord.Failed -> throw IllegalStateException(
                "LCC $phase benchmark refused a failed validation: " +
                    record.validation.differing,
            )
            else -> error("LCC $phase benchmark returned unexpected record $record")
        }
        return (figure(validated) as? Figure.Duration)?.nanos
            ?: error("LCC $phase benchmark requires a REAL duration figure")
    }

    private fun datasetRef(): DatasetRef = when (dataset) {
        Datasets.EXAMPLE_DIRECTED.name -> Datasets.EXAMPLE_DIRECTED
        Datasets.EXAMPLE_UNDIRECTED.name -> Datasets.EXAMPLE_UNDIRECTED
        else -> error("JMH supplied unknown Graphalytics dataset '$dataset'")
    }

    private fun currentMeasuringJvm(): MeasuringJvm = BoundedReadFixtures.thisProcessMeasuringJvm()

    private fun harnessCommitSha(): String {
        val configured = System.getProperty(BoundedReadFixtures.HARNESS_SHA_PROPERTY)
        if (!configured.isNullOrBlank()) return configured

        val process = ProcessBuilder("git", "rev-parse", "HEAD")
            .redirectErrorStream(true)
            .start()
        val sha = process.inputStream.bufferedReader().use { it.readText().trim() }
        check(process.waitFor() == 0 && sha.matches(Regex("[0-9a-f]{40}"))) {
            "set -D${BoundedReadFixtures.HARNESS_SHA_PROPERTY}=<commit> when the JMH " +
                "benchmark is run outside a Git checkout"
        }
        return sha
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

}
